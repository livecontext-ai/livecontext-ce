package com.apimarketplace.agent.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Known model categories. The DB constraint on
 * {@link ModelCategorySettingsEntity#getCategory()} is intentionally
 * permissive (any lowercase snake_case identifier) so adding a new category
 * is a code-only change - extend this enum, expose it in the admin UI tabs,
 * and ship.
 *
 * <p>Forward-extensibility examples (not yet implemented):
 * {@code video_generation}, {@code file_processing}, {@code embedding},
 * {@code audio}.
 *
 * <p>Use {@link #isValidShape(String)} to validate a free-form category
 * string at API boundaries; use the enum {@link #of(String)} when callers
 * are restricted to known values.
 */
public enum ModelCategory {

    CHAT("chat"),
    BROWSER_AGENT("browser_agent"),
    IMAGE_GENERATION("image_generation");

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

    /** Stable iteration order for UI tab rendering / seeding. */
    public static Set<String> defaultKeys() {
        return Set.of(CHAT.key, BROWSER_AGENT.key, IMAGE_GENERATION.key);
    }

    /**
     * True iff a model with the given {@code mode} ({@code chat | image |
     * embedding | audio | null}) is eligible to surface under {@code category}.
     *
     * <p>Eligibility rules:
     * <ul>
     *   <li>{@code chat} and {@code browser_agent} → only chat-capable rows
     *       ({@code mode IS NULL OR mode = 'chat'}). Image-gen rows do NOT
     *       leak into chat tabs even if they share the same parent table.</li>
     *   <li>{@code image_generation} → only {@code mode = 'image'} rows. Chat
     *       models do NOT leak into the image-gen tab - they're not callable
     *       through the {@code image_generation} tool anyway.</li>
     *   <li>Unknown / future categories → permissive (returns true) so a new
     *       category can ship its own seed without a code change here. Tighten
     *       per-category as new modes land.</li>
     * </ul>
     *
     * <p>Mirrors the V156 backfill SQL filter ({@code mode IS NULL OR mode = 'chat'}
     * for chat/browser_agent ; {@code mode = 'image'} for image_generation).
     */
    public static boolean acceptsMode(String category, String mode) {
        if (category == null) return true;
        if (CHAT.key.equals(category) || BROWSER_AGENT.key.equals(category)) {
            return mode == null || "chat".equals(mode);
        }
        if (IMAGE_GENERATION.key.equals(category)) {
            return "image".equals(mode);
        }
        // Forward-extensibility: an unknown category lets every mode through
        // until a future code change tightens the contract.
        return true;
    }
}
