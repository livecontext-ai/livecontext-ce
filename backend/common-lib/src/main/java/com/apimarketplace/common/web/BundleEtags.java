package com.apimarketplace.common.web;

/**
 * Entity-tag helpers shared by the three signed-bundle download endpoints (model catalog,
 * skills, API catalog). Each bundle's ETag is its content checksum, so a CE poll that presents
 * the checksum it already applied gets a bodiless 304 instead of the payload.
 */
public final class BundleEtags {

    private BundleEtags() {
    }

    /**
     * RFC 9110 {@code If-None-Match}: a comma-separated list of entity tags, or
     * {@code *}. Weak prefixes are ignored because the comparison for a
     * conditional GET is the weak one. A blank header matches nothing.
     */
    public static boolean matches(String ifNoneMatch, String checksum) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || checksum == null || checksum.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2).trim();
            }
            if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
                tag = tag.substring(1, tag.length() - 1);
            }
            if (tag.equals(checksum)) {
                return true;
            }
        }
        return false;
    }

    /** A checksum as a strong entity tag. */
    public static String quoted(String checksum) {
        return "\"" + checksum + "\"";
    }
}
