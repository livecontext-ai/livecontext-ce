package com.apimarketplace.catalog.util;

/**
 * Delegates to the shared common-lib normalizer.
 * Kept for backward compatibility - existing callers in catalog-service use this package path.
 *
 * @see com.apimarketplace.common.icon.IconSlugNormalizer
 */
public final class IconSlugNormalizer {

    private IconSlugNormalizer() {}

    public static String normalize(String input) {
        return com.apimarketplace.common.icon.IconSlugNormalizer.normalize(input);
    }

    public static String deriveIconSlug(String apiName, String explicitIconSlug) {
        return com.apimarketplace.common.icon.IconSlugNormalizer.deriveIconSlug(apiName, explicitIconSlug);
    }

    /**
     * Functionally identical to {@link #normalize(String)} since May 2026
     * (one icon = one API). Kept as a separate method for semantic clarity.
     */
    public static String normalizeForKey(String input) {
        return com.apimarketplace.common.icon.IconSlugNormalizer.normalize(input);
    }

    public static String toIconUrl(String iconSlug) {
        return com.apimarketplace.common.icon.IconSlugNormalizer.toIconUrl(iconSlug);
    }
}
