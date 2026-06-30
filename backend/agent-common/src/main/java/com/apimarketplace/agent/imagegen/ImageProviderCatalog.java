package com.apimarketplace.agent.imagegen;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for the image-generation providers and their
 * billing keys. Read by:
 *
 * <ul>
 *   <li>{@code OpenAIImageProvider} / {@code GeminiImageProvider} in
 *       orchestrator-service to resolve the pseudo-model billing key from
 *       the request's quality parameter.</li>
 *   <li>{@code ImageProvidersHavePricingTest} in auth-service to assert
 *       every entry has a matching row in V141 (drift guard).</li>
 * </ul>
 *
 * <p><b>Pseudo-model billing keys</b> &mdash; the {@code model} column in
 * {@code auth.model_pricing} stores names like {@code gpt-image-1-low}.
 * These are NOT real OpenAI model identifiers. The HTTP request to OpenAI
 * carries {@code model='gpt-image-1'} + {@code quality='low'} as separate
 * fields. The pseudo-model is purely a billing discriminator that captures
 * the cost dimension (per-image price varies by quality) without leaking
 * provider-specific quality semantics into the pricing layer.
 *
 * <p><b>Per-image billing</b> &mdash; image generation uses
 * {@link com.apimarketplace.auth.service.ModelPricingService#calculateUnitCost}
 * which returns {@code input_rate × n + fixed_cost} (no /1000 divisor),
 * matching the per-image pricing semantics. The {@code n} passed to the
 * helper MUST be the actual image count returned by the provider, not the
 * requested count &mdash; per-image content moderation can reject a subset.
 */
public final class ImageProviderCatalog {

    private ImageProviderCatalog() {}

    /**
     * OpenAI image-generation models, square 1024x1024 only.
     *
     * <ul>
     *   <li>{@code gpt-image-2} - current default (released 2026-04-21):
     *       $0.006 / $0.053 / $0.211 per image for low / medium / high.
     *       NO mini variant - OpenAI didn't ship one.</li>
     *   <li>{@code gpt-image-1.5} - previous default, still available:
     *       $0.009 / $0.034 / $0.133 per image for low / medium / high.</li>
     *   <li>{@code gpt-image-1-mini} - cheapest tier: $0.005 / $0.011 /
     *       $0.036.</li>
     * </ul>
     *
     * <p>The legacy {@code gpt-image-1} (non-1.5, non-mini) is intentionally
     * absent: OpenAI keeps it available for back-compat but recommends
     * gpt-image-2 as the default. We only seed pricing for the recommended
     * lanes so the agent picker doesn't surface stale options.
     */
    public static final List<Entry> OPENAI = List.of(
            new Entry("openai", "gpt-image-2-low",          new BigDecimal("6")),
            new Entry("openai", "gpt-image-2-medium",       new BigDecimal("53")),
            new Entry("openai", "gpt-image-2-high",         new BigDecimal("211")),
            new Entry("openai", "gpt-image-1.5-low",        new BigDecimal("9")),
            new Entry("openai", "gpt-image-1.5-medium",     new BigDecimal("34")),
            new Entry("openai", "gpt-image-1.5-high",       new BigDecimal("133")),
            new Entry("openai", "gpt-image-1-mini-low",     new BigDecimal("5")),
            new Entry("openai", "gpt-image-1-mini-medium",  new BigDecimal("11")),
            new Entry("openai", "gpt-image-1-mini-high",    new BigDecimal("36"))
    );

    /**
     * Google image-generation models.
     *
     * <ul>
     *   <li>{@code gemini-2.5-flash-image} ("nano-banana") - $0.039/image
     *       (1024x1024). Low-latency, single quality tier.</li>
     *   <li>{@code gemini-3-pro-image} - $0.134/image (1k-2k tier).
     *       Higher fidelity, more expensive.</li>
     * </ul>
     */
    public static final List<Entry> GOOGLE = List.of(
            new Entry("google", "gemini-2.5-flash-image", new BigDecimal("39")),
            new Entry("google", "gemini-3-pro-image",     new BigDecimal("134"))
    );

    /** All image-generation pricing entries, in stable order for tests. */
    public static final List<Entry> ALL = List.copyOf(
            java.util.stream.Stream.concat(OPENAI.stream(), GOOGLE.stream()).toList()
    );

    /**
     * Maps an OpenAI {@code (modelFamily, quality)} pair to its pseudo-model
     * billing key. Quality is matched case-insensitively. Model family is
     * one of {@code "gpt-image-2"}, {@code "gpt-image-1.5"} or
     * {@code "gpt-image-1-mini"}.
     */
    public static final Map<String, String> OPENAI_QUALITY_TO_BILLING_MODEL = Map.ofEntries(
            Map.entry("gpt-image-2:low",          "gpt-image-2-low"),
            Map.entry("gpt-image-2:medium",       "gpt-image-2-medium"),
            Map.entry("gpt-image-2:high",         "gpt-image-2-high"),
            Map.entry("gpt-image-1.5:low",        "gpt-image-1.5-low"),
            Map.entry("gpt-image-1.5:medium",     "gpt-image-1.5-medium"),
            Map.entry("gpt-image-1.5:high",       "gpt-image-1.5-high"),
            Map.entry("gpt-image-1-mini:low",     "gpt-image-1-mini-low"),
            Map.entry("gpt-image-1-mini:medium",  "gpt-image-1-mini-medium"),
            Map.entry("gpt-image-1-mini:high",    "gpt-image-1-mini-high")
    );

    /**
     * Resolves the pseudo-model billing key for an OpenAI image-generation
     * call. Both {@code modelFamily} and {@code quality} are required and
     * matched case-insensitively. Unknown combinations return
     * {@link Optional#empty()} so the caller can fail fast with
     * {@code INVALID_PARAMETER_VALUE} rather than charging at a default rate.
     */
    public static Optional<String> openAiBillingModel(String modelFamily, String quality) {
        if (modelFamily == null || quality == null) return Optional.empty();
        String key = OPENAI_QUALITY_TO_BILLING_MODEL.get(
                modelFamily.toLowerCase().trim() + ":" + quality.toLowerCase().trim());
        return Optional.ofNullable(key);
    }

    /** Real OpenAI model identifiers sent in the HTTP request body. */
    public static final java.util.Set<String> OPENAI_MODEL_FAMILIES =
            java.util.Set.of("gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini");

    /** Real Google model identifiers - same as the billing key (no quality tiers). */
    public static final java.util.Set<String> GOOGLE_MODELS =
            java.util.Set.of("gemini-2.5-flash-image", "gemini-3-pro-image");

    /** A single (provider, model, perImageRate) tuple. */
    public record Entry(String provider, String model, BigDecimal perImageRate) {}
}
