package com.apimarketplace.conversation.service.ai.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 4a.2 - config for Just-In-Time tools-schema routing. Read by
 * {@link ModelTierMapper} to decide whether a given model sees the slim
 * (names-only) tools prefix or the full schema.
 *
 * <p><b>Layout</b> (application.yml):
 * <pre>
 * conversation:
 *   jit:
 *     schemas:
 *       mode-by-tier:       # tier → SchemaMode (SLIM | FULL)
 *         top: SLIM
 *         high: SLIM
 *         mid: SLIM
 *         budget: FULL
 *       model-overrides:    # modelId → SchemaMode, wins over tier lookup
 *         glm-5-turbo: FULL   # weak LLM classified as "high" for routing/pricing
 *                             # but cannot bootstrap from a help directive
 *       unknown-model-default: FULL
 * </pre>
 *
 * <p><b>Why both a tier map and model overrides.</b> The platform's {@code tier}
 * field is a quality/pricing label, not a tool-schema-handling label. Most
 * high-tier models can read a slim tools prefix and bootstrap with {@code help}
 * calls, but some (like {@code glm-5-turbo}) are weak at instruction-following
 * despite their label. Per-model overrides let us keep the quality taxonomy
 * intact while still protecting tool accuracy from the actual outliers.
 *
 * <p><b>Fail-safe default.</b> {@code unknown-model-default = FULL}: an
 * unclassified model gets the full schema, so a misconfigured or freshly added
 * model never silently regresses tool accuracy. Wrong guess toward FULL just
 * wastes tokens; wrong guess toward SLIM breaks tool calls.
 */
@Configuration
@ConfigurationProperties(prefix = "conversation.jit.schemas")
public class JitSchemaProperties {

    /**
     * Map from model tier ({@code top | high | mid | budget} - the 4-tier
     * taxonomy used by {@code ModelCatalogService}) to the
     * {@link SchemaMode} the LLM will receive. Tier casing is normalised to
     * lowercase on lookup.
     */
    private Map<String, SchemaMode> modeByTier = defaultModeByTier();

    /**
     * Per-model overrides keyed by {@code modelId} exactly as declared in the
     * YAML catalog (case-sensitive - matches the {@code AvailableModel.modelId}
     * string). A match here wins over the tier lookup and the unknown default.
     */
    private Map<String, SchemaMode> modelOverrides = new HashMap<>();

    /**
     * Mode used when {@code modelId} is not in {@link #modelOverrides} AND
     * its tier is not in {@link #modeByTier} (unknown/missing tier). Defaults
     * to {@link SchemaMode#FULL} so misconfiguration degrades cost, not accuracy.
     */
    private SchemaMode unknownModelDefault = SchemaMode.FULL;

    private static Map<String, SchemaMode> defaultModeByTier() {
        // Baseline: every labelled tier ships SLIM except budget; budget stays FULL
        // because those models are selected specifically for being cheap/weak and
        // cannot reliably bootstrap from a help directive.
        Map<String, SchemaMode> m = new HashMap<>();
        m.put("top", SchemaMode.SLIM);
        m.put("high", SchemaMode.SLIM);
        m.put("mid", SchemaMode.SLIM);
        m.put("budget", SchemaMode.FULL);
        return m;
    }

    public Map<String, SchemaMode> getModeByTier() {
        return modeByTier;
    }

    public void setModeByTier(Map<String, SchemaMode> modeByTier) {
        this.modeByTier = modeByTier != null ? modeByTier : defaultModeByTier();
    }

    public Map<String, SchemaMode> getModelOverrides() {
        return modelOverrides;
    }

    public void setModelOverrides(Map<String, SchemaMode> modelOverrides) {
        this.modelOverrides = modelOverrides != null ? modelOverrides : new HashMap<>();
    }

    public SchemaMode getUnknownModelDefault() {
        return unknownModelDefault;
    }

    public void setUnknownModelDefault(SchemaMode unknownModelDefault) {
        this.unknownModelDefault = unknownModelDefault != null ? unknownModelDefault : SchemaMode.FULL;
    }
}
