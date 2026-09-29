package com.apimarketplace.auth.util;

/**
 * The one canonical form of an email address for identity decisions: invitations
 * (invite, accept, decline, inbox), local password registration / login / reset, and
 * the Keycloak {@code email_verified} sync. Every path that compares two addresses
 * must go through here so they all agree.
 *
 * <p>Trimmed, with ONLY the ASCII letters A-Z lowercased. Deliberately not
 * {@link String#equalsIgnoreCase} nor {@link String#toLowerCase()}: Unicode case
 * folding maps look-alikes such as U+017F (long s) to 's' and U+212A (Kelvin sign)
 * to 'k', so an account registered as "admin&lt;U+017F&gt;@..." would have matched an
 * invitation for "admins@...".
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /** Canonical form, or {@code null} for a null input. */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /** True when both addresses are non-null, non-blank and equal once normalized. */
    public static boolean matches(String a, String b) {
        String na = normalize(a);
        return na != null && !na.isEmpty() && na.equals(normalize(b));
    }
}
