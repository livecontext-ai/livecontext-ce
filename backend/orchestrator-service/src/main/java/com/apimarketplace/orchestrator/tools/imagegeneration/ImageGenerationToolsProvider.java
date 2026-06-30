package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.imagegen.ImageProviderCatalog;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.common.ToolResultPersistEnricher;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * Unified facade for image generation. Exposes ONE MCP tool
 * {@code image_generation} with an {@code action} dispatcher
 * (mirrors {@code web_search} / {@code agent} / {@code workflow}). For now
 * a single execution action ({@code generate}) plus {@code help}; future
 * additions ({@code edit}, {@code variation}) plug in via the same
 * dispatcher.
 *
 * <p><b>Help-first pattern</b> &mdash; the tool description stays compact
 * (paid in every prompt the tool surfaces in) and the verbose catalog
 * (providers, pseudo-model billing keys, per-image credit cost) is loaded
 * on demand via {@code action='help'}, matching
 * {@code DefaultSystemPrompts.HELP_FIRST}.
 *
 * <p>Persistence (an Interface entity for chat-side rendering) is wired in
 * task #10 - for now successful results carry inline base64 in
 * {@code data.images[]}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "image-generation.enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenerationToolsProvider implements ToolsProvider {

    private static final List<String> VALID_ACTIONS = List.of("generate", "help");

    private final ImageGenerationModule module;
    private final InterfaceClient interfaceClient;
    /**
     * Optional - null on CE deployments without billing wired. When present,
     * the {@code help} payload's {@code available_models} is filtered through
     * the V156 per-category sidecar (admin can disable {@code gpt-image-1.5-low}
     * for the {@code image_generation} category without code changes). When
     * null, falls back to the static {@link ImageProviderCatalog} list.
     */
    private final AgentClient agentClient;

    @Autowired
    public ImageGenerationToolsProvider(ImageGenerationModule module,
                                         InterfaceClient interfaceClient,
                                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                                         AgentClient agentClient) {
        this.module = module;
        this.interfaceClient = interfaceClient;
        this.agentClient = agentClient;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.IMAGE_GENERATION;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                        ToolExecutionContext context) {
        if (!"image_generation".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = parameters == null ? null : (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            if ("help".equals(action)) {
                return ToolExecutionResult.success(buildHelpPayload());
            }
            if (module.canHandle(action)) {
                ToolExecutionResult raw = module.execute(action, parameters, context.tenantId(), context)
                        .orElse(ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Image generation failed"));
                if (raw.success()) {
                    return persistAndEnrich(raw, parameters, context);
                }
                return raw;
            }
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        } catch (Exception e) {
            log.error("Error executing image_generation action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    /**
     * Persist the generated images as an Interface entity and enrich the
     * tool result with the {@code [visualize:image_generation:&lt;id&gt;]}
     * marker + {@code metadata.visualization} so the chat side panel can
     * render a card. Delegates the generic plumbing to
     * {@link ToolResultPersistEnricher}; supplies only the
     * image-gen-specific persist lambda.
     *
     * <p>If {@code conversationId} / {@code __messageId__} are absent (e.g.
     * workflow-only invocation) the helper returns the original result
     * unchanged - the agent still receives the inline base64 in
     * {@code data.images[]}.
     */
    private ToolExecutionResult persistAndEnrich(ToolExecutionResult raw,
                                                  Map<String, Object> parameters,
                                                  ToolExecutionContext context) {
        ToolResultPersistEnricher.PersistFn persistFn = (ctx, params, originalData) -> {
            if (!(originalData instanceof Map<?, ?> dataMap)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataMap;

            Map<String, Object> creds = ctx.credentials();
            String conversationId = (String) creds.get("conversationId");
            String messageId = (String) creds.get("__messageId__");
            String agentId = (String) creds.get("__agentId__");

            String prompt = (String) params.get("prompt");
            String name = prompt != null && prompt.length() > 80
                    ? prompt.substring(0, 80) + "…"
                    : (prompt != null ? prompt : "Generated image");

            ImageGenerationInterfaceRequest req = new ImageGenerationInterfaceRequest();
            req.setName(name);
            req.setConversationId(conversationId);
            req.setMessageId(messageId);
            req.setAgentId(agentId);
            req.setData(data); // module's data already has images[], provider, billing_model, prompt
            // Stamp organization_id so org-teammates can read the persisted interface
            // (sister bug of WebSearchToolsProvider - image-gen card in chat would
            // show "Failed to load" for org-teammates without this).
            req.setOrganizationId(ctx.orgId());

            InterfaceDto persisted = interfaceClient.createOrUpdateImageGenerationInterface(req, ctx.tenantId());
            if (persisted == null || persisted.getId() == null) return null;
            return new ToolResultPersistEnricher.PersistedInterface(
                    persisted.getId().toString(), persisted.getName());
        };

        // Single contract: by the time the strip hook runs, every entry in
        // {@code images[]} MUST already be a FileRef Map produced by the
        // catalog dehydrator (the canonical chokepoint, fixed in
        // {@link com.apimarketplace.catalog.service.ToolExecutionManager} so
        // the dehydrator runs BEFORE OutputProjector and can no longer be
        // bypassed by Jackson's String→BinaryNode coercion).
        //
        // The orchestrator no longer has a fallback uploader - that was a
        // band-aid masking the catalog ordering bug. If a raw {@code base64}
        // entry reaches this hook today it means the catalog regressed; we
        // log loudly and DROP the entry so the agent doesn't ship a path-less
        // ghost record (the symptom of the OpenAI 28/04 16:16 incident).
        // Failing visibly is better than re-uploading from a second code path
        // that obscures the real problem.
        ToolResultPersistEnricher.StripFn stripImageBytes = (ctx, params, originalData, persisted) -> {
            if (!(originalData instanceof Map<?, ?> dataMap)) return originalData;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataMap;
            Object imagesObj = data.get("images");
            if (!(imagesObj instanceof java.util.List<?> images) || images.isEmpty()) return data;

            java.util.List<Object> rebuilt = new java.util.ArrayList<>(images.size());
            int dehydrated = 0;
            int dropped = 0;
            for (Object img : images) {
                if (!(img instanceof Map<?, ?> imgMap)) {
                    rebuilt.add(img);
                    continue;
                }
                java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : imgMap.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                if (copy.get("path") instanceof String pathStr && !pathStr.isBlank()) {
                    // Catalog dehydrator did its job - pass through + stamp
                    // {@code persisted:true} for the agent-visible contract.
                    copy.put("persisted", Boolean.TRUE);
                    rebuilt.add(copy);
                    dehydrated++;
                    continue;
                }
                // Raw base64 reached us → catalog regressed. Drop loudly.
                if (copy.containsKey("base64") || copy.containsKey("mime_type") || copy.containsKey("mimeType")) {
                    log.error("image_generation: dropping image - catalog dehydrator returned raw base64 instead of FileRef "
                            + "(provider={}, interfaceId={}). Investigate ToolExecutionManager dehydrator/projector ordering.",
                            data.get("provider"), persisted.id());
                    dropped++;
                    continue;
                }
                rebuilt.add(copy);
            }
            if (dropped > 0) {
                log.warn("image_generation: dropped {} image(s) due to missing FileRef (catalog regression) - interfaceId={}",
                        dropped, persisted.id());
            }
            log.debug("image_generation: {} FileRef-shaped image(s) passthrough (interfaceId={})",
                    dehydrated, persisted.id());
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(data);
            out.put("images", rebuilt);
            return out;
        };

        return ToolResultPersistEnricher.enrichAndPersist(
                raw, parameters, context, "image_generation",
                persistFn, /* postPersistHook */ null, stripImageBytes);
    }

    private AgentToolDefinition buildUnifiedTool() {
        var params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("generate | help")
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                stringParam("prompt", "Image description (generate). 1-32000 chars.", false),
                stringParam("provider", "'openai' (default) | 'google' (generate). "
                        + "Call action='help' for the full catalog.", false),
                stringParam("model", "Real model id, e.g. 'gpt-image-1.5' (default), "
                        + "'gpt-image-1-mini', 'gemini-2.5-flash-image', 'gemini-3-pro-image' (generate). "
                        + "MUST come from action='help'.available_models.", false),
                stringParam("quality", "'low' | 'medium' (default) | 'high'. OpenAI only - "
                        + "Google models have a single quality tier (generate).", false),
                intParam("n", "Image count, default 1, cap 10 (generate). "
                        + "OpenAI: single API call. Google: serial calls per image.", false, 1),
                stringParam("size", "'1024x1024' (default; only square supported in v1)", false)
        );

        String description = "Generate images from a text prompt.\n"
                + "- generate: returns base64 PNG(s). Bills per image returned, post-success only.\n"
                + "- help: catalog of providers, models, per-image credit cost, examples.\n"
                + "Costs vary by model+quality (e.g. gpt-image-1-mini low = 5 credits, "
                + "gpt-image-1.5 high = 133 credits). Call action='help' once before first use.";

        return AgentToolDefinition.builder()
                .name("image_generation")
                .description(description)
                .category(ToolCategory.IMAGE_GENERATION)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call image_generation(action='help') for the (provider, model, quality) "
                        + "catalog and per-image credit costs.")
                .requiresAuth(false)
                .tags(List.of("image", "generation", "openai", "google", "gpt-image", "gemini"))
                .timeoutMs(600_000L)
                .build();
    }

    /**
     * Help payload built from {@link ImageProviderCatalog} so the catalog
     * is the single source of truth (drift-guarded by
     * {@code ImageProvidersHavePricingTest}).
     */
    private Map<String, Object> buildHelpPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "IMAGE_GENERATION TOOL - text-to-image via OpenAI gpt-image-1.5/mini or "
                + "Google Gemini 2.5 Flash Image / 3 Pro Image. Returns FileRef records "
                + "(see concepts.file_ref) - no inline bytes. Bills per image RETURNED "
                + "(post-success only); per-image moderation can drop a subset.");

        // Actions
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("generate", Map.of(
                "summary", "Text-to-image. Pick provider+model from available_models.",
                "params", Map.of(
                        "prompt", "required - the text prompt",
                        "provider", "'openai' (default) | 'google'",
                        "model", "see available_models",
                        "quality", "'low' | 'medium' (default) | 'high' - OpenAI only",
                        "n", "1..10, default 1",
                        "size", "'1024x1024' (only)"),
                "returns", "{ images[]: {_type:'file', path, name, mimeType, size, persisted, revised_prompt?}, "
                        + "count, provider, billing_model, prompt }"));
        actions.put("help", Map.of(
                "summary", "This payload. No params."));
        out.put("actions", actions);

        // Available models (catalog-driven)
        out.put("available_models", buildAvailableModels());

        // Concepts
        out.put("concepts", Map.of(
                "billing", "1 credit ≈ $0.001. Cost = per-image rate × actualImageCount. "
                        + "Pre-flight rejects with QUOTA_EXCEEDED if pricing row missing.",
                "pseudo_model", "OpenAI billing splits by quality tier (gpt-image-1.5-low / -medium / -high). "
                        + "The HTTP request still uses the bare model name + quality flag; "
                        + "the suffixed key is for billing only.",
                "file_ref", "FileRef = {_type:'file', path, name, mimeType, size, persisted:true}. "
                        + "path is the durable identifier - pass the FileRef back into another tool to "
                        + "re-use the image. No expiring URL is included; downstream re-resolves via path.",
                "content_moderation", "OpenAI returns HTTP 400 with 'safety' in the message. "
                        + "Gemini returns no candidates. Both surface as VALIDATION_ERROR - try a different prompt."));

        // Examples
        out.put("examples", List.of(
                Map.of("action", "generate",
                        "prompt", "A serene Japanese garden at sunset, watercolor style",
                        "provider", "openai",
                        "model", "gpt-image-1.5",
                        "quality", "medium",
                        "n", 1),
                Map.of("action", "generate",
                        "prompt", "Photorealistic close-up of a banana on marble",
                        "provider", "google",
                        "model", "gemini-2.5-flash-image"),
                Map.of("action", "generate",
                        "prompt", "Logo: minimalist coffee bean, vector",
                        "provider", "openai",
                        "model", "gpt-image-1-mini",
                        "quality", "low",
                        "n", 4,
                        "comment", "Cheapest path: 5 × 4 = 20 credits")));

        return out;
    }

    /**
     * Renders the help payload's {@code available_models} structure.
     *
     * <p>V156: when {@link #agentClient} is wired, the list is filtered by
     * the per-category sidecar ({@code image_generation}). Models the
     * platform admin disabled in the admin UI's image-generation tab are
     * absent here, and the order reflects the admin's drag-and-drop ranking
     * - same source of truth as the chat help_models pattern.
     *
     * <p>Fallback: when {@code agentClient == null} (CE without billing
     * wired) OR the agent-service call fails, the renderer falls back to
     * the static {@link ImageProviderCatalog} list so the action stays
     * callable. The agent reads
     * {@code (provider, model, quality, credits_per_image)} as a flat list
     * - easy to filter by budget without parsing nested grouping.
     */
    private List<Map<String, Object>> buildAvailableModels() {
        Set<String> enabledBillingKeys = resolveEnabledBillingKeys();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        // OpenAI: each catalog entry is a (model, quality) pair encoded in the pseudo-model.
        for (ImageProviderCatalog.Entry e : ImageProviderCatalog.OPENAI) {
            String model = e.model();
            if (enabledBillingKeys != null && !enabledBillingKeys.contains(model)) continue;
            // Strip the quality suffix from the billing key to recover (modelFamily, quality)
            // for the agent. The HTTP request to OpenAI uses (modelFamily, quality) separately.
            String quality;
            String modelFamily;
            if (model.endsWith("-low")) { quality = "low"; modelFamily = model.substring(0, model.length() - 4); }
            else if (model.endsWith("-medium")) { quality = "medium"; modelFamily = model.substring(0, model.length() - 7); }
            else if (model.endsWith("-high")) { quality = "high"; modelFamily = model.substring(0, model.length() - 5); }
            else { quality = "medium"; modelFamily = model; }
            rows.add(Map.of(
                    "provider", e.provider(),
                    "model", modelFamily,
                    "quality", quality,
                    "credits_per_image", e.perImageRate().intValueExact(),
                    "size", "1024x1024"));
        }
        // Google: model itself is the billing key, single quality tier.
        for (ImageProviderCatalog.Entry e : ImageProviderCatalog.GOOGLE) {
            if (enabledBillingKeys != null && !enabledBillingKeys.contains(e.model())) continue;
            rows.add(Map.of(
                    "provider", e.provider(),
                    "model", e.model(),
                    "quality", "default",
                    "credits_per_image", e.perImageRate().intValueExact(),
                    "size", "1024x1024"));
        }
        return rows;
    }

    /**
     * Pull the set of model_ids enabled in the {@code image_generation}
     * category from agent-service. Returns {@code null} on any failure (or
     * when the client is unwired) so callers fall back to "show everything
     * from {@link ImageProviderCatalog}".
     *
     * <p>The agent-service endpoint already overlays the V156 sidecar
     * (rank + enabled per category) onto {@code agent.model_config_overrides},
     * removes rows where {@code enabled=false}, and sorts by rank. We just
     * read the resulting model_ids and intersect with the static catalog at
     * render time - that keeps the billing-key dictionary local to
     * orchestrator (no cross-service shape coupling on pseudo-model keys).
     */
    @SuppressWarnings("unchecked")
    private Set<String> resolveEnabledBillingKeys() {
        if (agentClient == null) return null;
        try {
            Map<String, Object> catalog = agentClient.getModelsInfo("image_generation");
            List<Map<String, Object>> providers =
                    (List<Map<String, Object>>) catalog.getOrDefault("providers", List.of());
            Set<String> ids = new HashSet<>();
            for (Map<String, Object> p : providers) {
                List<Map<String, Object>> models =
                        (List<Map<String, Object>>) p.getOrDefault("models", List.of());
                for (Map<String, Object> m : models) {
                    Object id = m.get("id");
                    if (id instanceof String s) ids.add(s);
                }
            }
            // No image-gen rows in the catalog response = something's wrong
            // (V157 should have seeded 8 rows). Don't filter blindly - fall
            // back to the static list so the action stays useful instead of
            // returning empty.
            if (ids.isEmpty()) return null;
            return ids;
        } catch (Exception e) {
            log.warn("image_generation help: failed to fetch per-category catalog from "
                    + "agent-service, falling back to ImageProviderCatalog: {}", e.getMessage());
            return null;
        }
    }

}
