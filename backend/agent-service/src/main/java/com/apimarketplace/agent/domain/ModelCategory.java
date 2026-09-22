package com.apimarketplace.agent.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Known model categories. The DB constraint on
 * {@link ModelCategorySettingsEntity#getCategory()} is intentionally
 * permissive (any lowercase snake_case identifier) so adding a new category
 * is a code-only change - extend this enum, give it a reader, and ship.
 *
 * <p>Forward-extensibility examples (none of them implemented):
 * {@code file_processing}, {@code embedding}, {@code transcription}.
 *
 * <p><b>Only {@code chat} and {@code browser_agent} have an admin screen.</b>
 * {@code classification} has none yet: it exists so the eligibility rule can keep decision
 * models out of the conversational surfaces and give the classify node's picker something
 * to ask for. The five
 * {@code <format>_generation} constants are kept because rows were written
 * under them and callers may still ask for them, but nothing offers them any
 * more: a generation model has no ranking to give (the caller names its model,
 * and a video model is no substitute for a voice one), nothing to enable (it
 * exists because its catalog endpoint exists) and no per-token price (it bills
 * per image or per second, against a platform credential). Do not treat them as
 * a place to surface generation models.
 *
 * <p>Use {@link #isValidShape(String)} to validate a free-form category
 * string at API boundaries; use the enum {@link #of(String)} when callers
 * are restricted to known values.
 */
public enum ModelCategory {

    CHAT("chat"),
    BROWSER_AGENT("browser_agent"),
    CLASSIFICATION("classification"),
    IMAGE_GENERATION("image_generation"),
    VIDEO_GENERATION("video_generation"),
    AUDIO_GENERATION("audio_generation"),
    VOICE_GENERATION("voice_generation"),
    MUSIC_GENERATION("music_generation");

    /**
     * Suffix marking a category as "one generation format". Everything before it
     * is the {@code mode} its models carry, so
     * {@code video_generation} accepts exactly {@code mode='video'}.
     *
     * <p>The convention is what keeps the eligibility rule generic: a constant
     * of this shape needs no change to {@link #acceptsMode(String, String)} and
     * no migration (the V156 CHECK already accepts any snake_case identifier).
     * Adding one is nonetheless not the way to surface a generation model: see
     * the class javadoc for why none of them has a screen.
     */
    public static final String GENERATION_SUFFIX = "_generation";

    /**
     * The {@code mode} carried by a model that returns a TYPED DECISION rather than
     * text: a choice among declared options, a score, a boolean, each with calibrated
     * probabilities. TypeSafe's Jev is the first.
     *
     * <p><b>This constant is the cloisonnement, and it is load-bearing.</b> A decision
     * model cannot hold a conversation, cannot call a tool and cannot emit a token of
     * prose, so an Agent node or a chat pointed at one is not degraded, it is broken.
     * {@link #acceptsMode} admits {@code chat} and {@code browser_agent} only for
     * {@code mode IS NULL OR mode = 'chat'}, and {@code ModelCatalogService} resolves
     * the category-less global path (the chat picker, the flat model list, the default
     * model pick) as {@code chat}.
     *
     * <p><b>It only works because the mode reaches BOTH halves of the catalog.</b> That
     * catalog is assembled from database rows, which carry a {@code mode} column, and from
     * YAML-declared models, which carry whatever {@code LLMProvider.getModelMode} reports.
     * Dropping the database override from the overlay is NOT enough on its own: the YAML
     * model survives it and stays in the picker. So this value has to be written in the
     * migration AND declared by the provider, and it is the same spelling in both because
     * it is defined once, in {@link com.apimarketplace.agent.provider.LLMProvider}.
     *
     * <p>The mode says what the MODEL is; {@link #CLASSIFICATION} says which SURFACE
     * offers it. They are deliberately two words: a future guardrail surface would
     * accept this same mode under a category of its own.
     */
    public static final String DECISION_MODE =
            com.apimarketplace.agent.provider.LLMProvider.MODE_DECISION;

    private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final String key;

    ModelCategory(String key) { this.key = key; }

    public String key() { return key; }

    /** Strict lookup - throws on unknown keys. */
    public static ModelCategory of(String key) {
        for (ModelCategory c : values()) {
            if (c.key.equals(key)) return c;
        }
        throw new IllegalArgumentException("Unknown ModelCategory: " + key);
    }

    /** Permissive shape check matching the V156 CHECK constraint. */
    public static boolean isValidShape(String key) {
        return key != null && key.length() <= 32 && SHAPE.matcher(key).matches();
    }

    /** Stable iteration order for seeding. */
    public static Set<String> defaultKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (ModelCategory c : values()) keys.add(c.key);
        return java.util.Collections.unmodifiableSet(keys);
    }

    /** True when this category represents one generation format. */
    public static boolean isGeneration(String category) {
        return category != null && category.endsWith(GENERATION_SUFFIX)
                && category.length() > GENERATION_SUFFIX.length();
    }

    /**
     * The model {@code mode} a generation category accepts, derived from its
     * name ({@code video_generation} to {@code video}). Null for a category
     * that is not a generation format.
     */
    public static String modeForGenerationCategory(String category) {
        if (!isGeneration(category)) return null;
        return category.substring(0, category.length() - GENERATION_SUFFIX.length());
    }

    /**
     * True iff a model with the given {@code mode} ({@code chat | image |
     * embedding | audio | null}) is eligible to surface under {@code category}.
     *
     * <p>Eligibility rules:
     * <ul>
     *   <li>{@code chat} and {@code browser_agent} → only chat-capable rows
     *       ({@code mode IS NULL OR mode = 'chat'}). Generation rows do NOT
     *       leak into chat tabs even if they share the same parent table.</li>
     *   <li>Any {@code <format>_generation} category → only {@code mode =
     *       '<format>'} rows. So {@code image_generation} accepts
     *       {@code mode='image'} and {@code video_generation} accepts
     *       {@code mode='video'}, derived from the name rather than listed
     *       here. Adding a format is a constant in this enum and nothing
     *       else.</li>
     *   <li>{@code classification} → only {@code mode = 'decision'} rows. Chat
     *       models are NOT admitted here even though the Classify NODE runs on
     *       either engine: this category answers "which decision models does the
     *       platform expose", and the node's picker unions that answer with the
     *       chat list rather than replacing it. Without this branch the clause
     *       below would let every chat model through, which is the trap a new
     *       category walks into.</li>
     *   <li>Unknown / future categories → permissive (returns true) so a new
     *       category can ship its own seed without a code change here.</li>
     * </ul>
     *
     * <p>Mirrors the V156 backfill SQL filter ({@code mode IS NULL OR mode = 'chat'}
     * for chat/browser_agent ; {@code mode = '<format>'} for a generation category).
     */
    public static boolean acceptsMode(String category, String mode) {
        if (category == null) return true;
        if (CHAT.key.equals(category) || BROWSER_AGENT.key.equals(category)) {
            return mode == null || "chat".equals(mode);
        }
        if (CLASSIFICATION.key.equals(category)) {
            return DECISION_MODE.equals(mode);
        }
        String generationMode = modeForGenerationCategory(category);
        if (generationMode != null) {
            return generationMode.equals(mode);
        }
        // Forward-extensibility: an unknown category lets every mode through
        // until a future code change tightens the contract.
        return true;
    }
}
