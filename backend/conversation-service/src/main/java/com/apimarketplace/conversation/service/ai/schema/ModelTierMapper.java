package com.apimarketplace.conversation.service.ai.schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Stage 4a.2 - resolve a {@link SchemaMode} for one (provider, modelId, tier)
 * triple so the conversation-service can serve the slim or full tools prefix
 * per turn.
 *
 * <p><b>Resolution order</b> (first match wins):
 * <ol>
 *   <li><b>Per-model override</b> - {@code model-overrides[modelId]} from
 *   {@link JitSchemaProperties}. Lets us force {@link SchemaMode#FULL} on
 *   weak-at-tool-use models that still carry a high quality tier
 *   (e.g. {@code glm-5-turbo=high} but cannot bootstrap from a help
 *   directive).</li>
 *   <li><b>Tier lookup</b> - {@code mode-by-tier[tier]} on the tier string
 *   from the model's {@code AvailableModel.tier()} field. Tier casing is
 *   normalised to lowercase; whitespace is trimmed. Any tier not explicitly
 *   configured falls through to the unknown default.</li>
 *   <li><b>Unknown default</b> - {@code unknown-model-default}, which ships
 *   {@link SchemaMode#FULL} by default so an unclassified model never silently
 *   regresses tool accuracy.</li>
 * </ol>
 *
 * <p><b>Null / blank inputs.</b> A null or blank {@code modelId} short-circuits
 * to the unknown default immediately - there is no model to look up. A null
 * or blank {@code tier} skips the tier lookup and falls through to the default
 * (the override is still consulted first in case the modelId is known).
 *
 * <p><b>Stateless.</b> Pure lookup against {@link JitSchemaProperties}. No
 * caching here - the properties map is already a hash lookup, and the
 * mapper is called at most once per conversation turn's tool-prefix build.
 *
 * <p><b>Not wired to {@link SchemaSlimmer} directly.</b> The mapper answers
 * "slim or full?"; the caller (tools-prefix builder at Stage 4a.wiring) then
 * chooses whether to run {@link SchemaSlimmer#minimize} or pass the
 * {@link com.apimarketplace.agent.domain.ToolDefinition} through untouched.
 * This keeps the slimmer pure and the tier policy in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelTierMapper {

    private final JitSchemaProperties properties;

    /**
     * Resolve the schema mode for a single model. Never returns {@code null}.
     *
     * @param provider provider registry name (e.g. {@code "anthropic"}) -
     *                 currently unused in the lookup but accepted so the
     *                 signature is stable if we later key overrides by
     *                 {@code provider:modelId}
     * @param modelId  model id exactly as declared in the AI-provider catalog;
     *                 {@code null}/blank → {@link JitSchemaProperties#getUnknownModelDefault()}
     * @param tier     the 4-tier label from the catalog
     *                 ({@code top | high | mid | budget}); {@code null}/blank
     *                 falls through to the unknown default
     */
    public SchemaMode resolve(String provider, String modelId, String tier) {
        if (modelId == null || modelId.isBlank()) {
            return properties.getUnknownModelDefault();
        }

        SchemaMode override = properties.getModelOverrides().get(modelId);
        if (override != null) {
            log.debug("ModelTierMapper: model-override hit modelId={} → {}", modelId, override);
            return override;
        }

        if (tier != null && !tier.isBlank()) {
            SchemaMode byTier = properties.getModeByTier().get(tier.trim().toLowerCase(Locale.ROOT));
            if (byTier != null) {
                log.debug("ModelTierMapper: tier hit modelId={} tier={} → {}", modelId, tier, byTier);
                return byTier;
            }
        }

        SchemaMode fallback = properties.getUnknownModelDefault();
        log.debug("ModelTierMapper: fallback modelId={} tier={} → {}", modelId, tier, fallback);
        return fallback;
    }
}
