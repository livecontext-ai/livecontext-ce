package com.apimarketplace.common.icon;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonical icon-slug normalizer shared across all services.
 * Format: lowercase alphanumeric only, no separators ([a-z0-9]+).
 *
 * Used by catalog-service (import + runtime), auth-service (credential keys),
 * conversation-service (request_credential card), and frontend icon rendering
 * (path: /icons/services/{slug}.svg).
 */
public final class IconSlugNormalizer {

    private IconSlugNormalizer() {}

    /**
     * Normalize any input to the canonical icon slug format: [a-z0-9]+.
     * Strips accents, removes "-api" suffix, lowercases, removes all non-alphanumeric.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("(?i)-api$", "");
        return normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
    }

    /**
     * Derives the icon slug from an API name, with optional explicit override.
     */
    public static String deriveIconSlug(String apiName, String explicitIconSlug) {
        if (explicitIconSlug != null && !explicitIconSlug.isBlank()) {
            return normalize(explicitIconSlug);
        }
        return normalize(apiName);
    }

    /**
     * Normalize to a unique-per-API credential key. Functionally identical to
     * {@link #normalize(String)} since May 2026 (one icon = one API). Kept as a
     * separate method because visual slug and credential key are semantically
     * distinct and may diverge again.
     */
    public static String normalizeForKey(String input) {
        return normalize(input);
    }

    /**
     * Builds the full icon URL path from an icon slug.
     * Falls back to the generic MCP icon if slug is empty.
     */
    public static String toIconUrl(String iconSlug) {
        if (iconSlug == null || iconSlug.isBlank()) {
            return "/icons/services/mcp.svg";
        }
        return "/icons/services/" + iconSlug + ".svg";
    }
}
