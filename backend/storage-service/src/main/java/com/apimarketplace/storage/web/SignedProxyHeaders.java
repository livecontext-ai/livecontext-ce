package com.apimarketplace.storage.web;

import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Set;

/**
 * Headers every {@code /api/files/proxy-signed} response carries so a signed link can never run
 * script on the app origin.
 *
 * <p>The signed proxy serves user files inline from the platform's own origin. An HTML or SVG file
 * opened there as a top-level page would execute its script with that origin. Getting such a link
 * used to take a {@code core:public_link} node; {@code GET /api/files/by-id/{id}/signed-url} makes it
 * one call for any file its caller can read, so the response itself now refuses the role:
 * {@code nosniff} on everything, and a {@code sandbox} policy for active types. An {@code <img>}, {@code <video>} or {@code <audio>} is unaffected
 * (a subresource ignores the policy of the response it loads).
 */
public final class SignedProxyHeaders {

    /**
     * {@code sandbox} alone: no script, no form, no popup, an opaque origin - but the page still
     * loads its own images and stylesheets, so an HTML report shared through {@code core:public_link}
     * keeps rendering. Only what could act on the app origin is taken away.
     */
    static final String NO_SCRIPT_POLICY = "sandbox";

    private static final Set<String> ACTIVE = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml",
            "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript");

    private SignedProxyHeaders() {
    }

    static boolean isActive(String mimeType) {
        if (mimeType == null) return false;
        String bare = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return ACTIVE.contains(bare);
    }

    /** Adds the guard headers to {@code builder} for a body of type {@code mimeType}. */
    public static <B extends ResponseEntity.HeadersBuilder<B>> B guard(B builder, String mimeType) {
        builder.header("X-Content-Type-Options", "nosniff");
        if (isActive(mimeType)) {
            builder.header("Content-Security-Policy", NO_SCRIPT_POLICY);
        }
        return builder;
    }
}
