package com.apimarketplace.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Generates a Gmail-style "initials on colored background" SVG avatar for a user.
 *
 * Used as the default-avatar fallback by {@code UserController.getAvatar} when
 * {@code auth.users.avatar_url} is null (no uploaded photo, no OAuth picture).
 *
 * <p><b>Determinism:</b> output bytes are a pure function of (firstName, lastName,
 * username, email). The chosen color is derived from a SHA-256 of the lowercased
 * email so it stays stable when the user renames themselves.</p>
 *
 * <p><b>XSS:</b> the initials are HTML-escaped before being injected into the
 * SVG {@code <text>} node - display names can contain arbitrary unicode.</p>
 */
public final class InitialsAvatarGenerator {

    private InitialsAvatarGenerator() {}

    /** Material-design 12-color palette, picked for white-text legibility. */
    private static final String[] PALETTE = {
            "#DB4437", // red 500
            "#E91E63", // pink 500
            "#9C27B0", // purple 500
            "#673AB7", // deep-purple 500
            "#3F51B5", // indigo 500
            "#4285F4", // blue 500
            "#039BE5", // light-blue 600
            "#00ACC1", // cyan 600
            "#0F9D58", // green (gmail green)
            "#43A047", // light-green 600
            "#F4B400", // amber (gmail yellow)
            "#FF7043", // deep-orange 400
    };

    /** Generate the SVG bytes. Never returns null. */
    public static byte[] generateSvg(String firstName, String lastName, String username, String email) {
        String initials = computeInitials(firstName, lastName, username, email);
        String seed = (email != null && !email.isBlank()) ? email : initials;
        String color = pickColor(seed);
        return buildSvg(initials, color).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate an "unknown user" SVG ({@code ?} initial, color seeded on the
     * given string). Used by the avatar endpoint when the requested {@code userId}
     * doesn't resolve to a row - keeps the response shape uniform across
     * existent / missing users so the endpoint isn't an enumeration oracle.
     */
    public static byte[] generateUnknownSvg(String colorSeed) {
        return buildSvg("?", pickColor(colorSeed)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Initials fallback chain:
     *   1. firstName[0] + lastName[0]               (Sarah Lambert → "SL")
     *   2. firstName 2 chars                         (Sarah          → "SA")
     *   3. username "two words" or first 2 chars     (ada lovelace   → "AL")
     *   4. email 2 chars                             (foo@bar        → "FO")
     *   5. "?"
     */
    static String computeInitials(String firstName, String lastName, String username, String email) {
        String f = trimToNull(firstName);
        String l = trimToNull(lastName);
        if (f != null && l != null) {
            return upperFirst(f) + upperFirst(l);
        }
        if (f != null) {
            return takeUpperPrefix(f, 2);
        }
        String u = trimToNull(username);
        if (u != null) {
            String[] parts = u.split("\\s+");
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return upperFirst(parts[0]) + upperFirst(parts[1]);
            }
            return takeUpperPrefix(u, 2);
        }
        String e = trimToNull(email);
        if (e != null) {
            return takeUpperPrefix(e, 2);
        }
        return "?";
    }

    /** Hash {@code seed} (lowercased) to a stable palette index. */
    static String pickColor(String seed) {
        if (seed == null || seed.isBlank()) {
            return PALETTE[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            int unsigned = ((hash[0] & 0xff) << 8) | (hash[1] & 0xff);
            return PALETTE[unsigned % PALETTE.length];
        } catch (NoSuchAlgorithmException impossible) {
            return PALETTE[Math.floorMod(seed.hashCode(), PALETTE.length)];
        }
    }

    private static String upperFirst(String s) {
        // Take the first code point (handles surrogate pairs + accented letters),
        // strip combining marks, uppercase.
        int cp = s.codePointAt(0);
        String head = new String(Character.toChars(cp));
        String stripped = Normalizer.normalize(head, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toUpperCase(Locale.ROOT);
    }

    private static String takeUpperPrefix(String s, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length() && sb.codePointCount(0, sb.length()) < n) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        String stripped = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String buildSvg(String initials, String hexColor) {
        // Font-size scales with initial count so 1-3 letter cases all stay centered.
        String fontSize = switch (initials.codePointCount(0, initials.length())) {
            case 1 -> "140";
            case 2 -> "110";
            default -> "90";
        };
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 256 256\">"
                + "<defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"
                + "<stop offset=\"0%\" stop-color=\"" + hexColor + "\"/>"
                + "<stop offset=\"100%\" stop-color=\"" + hexColor + "cc\"/>"
                + "</linearGradient></defs>"
                + "<rect width=\"256\" height=\"256\" fill=\"url(#g)\"/>"
                + "<text x=\"128\" y=\"128\" font-family=\"Helvetica,Arial,sans-serif\" font-size=\"" + fontSize
                + "\" font-weight=\"600\" fill=\"#ffffff\" text-anchor=\"middle\" dominant-baseline=\"central\">"
                + escapeXml(initials) + "</text></svg>";
    }
}
