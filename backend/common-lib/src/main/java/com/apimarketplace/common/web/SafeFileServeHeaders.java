package com.apimarketplace.common.web;

import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Set;

/**
 * Decides the protective headers for serving a STORED (user- or AI-supplied) file back to a
 * browser from the application origin.
 *
 * <p>Uploaded files are attacker-controlled in both bytes and declared type: any account holder
 * can upload {@code payload.svg} or a file declared {@code text/html}, then get a victim to open
 * the link. Served {@code inline} from the app origin, that content runs as a first-party
 * document, and the OIDC session lives in {@code localStorage} on that origin. The same object
 * is reachable through several endpoints (cloud and CE), so the rule lives here, once, and every
 * serve path calls it (LC-016 / LC-020).
 *
 * <p>Three rules, applied together:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} on every response, so a mislabelled type is never
 *       re-sniffed into an executable one.</li>
 *   <li>{@code inline} only for an ALLOW-list of types that cannot run script as a document
 *       (plus SVG, neutralised by the CSP below). Everything else, including unknown and absent
 *       types, is forced to {@code attachment}: the browser downloads it instead of rendering it.
 *       A subresource load ({@code <img>}, {@code <video>}, {@code <script src>}) ignores
 *       {@code Content-Disposition}, so interface assets keep working.</li>
 *   <li>A deny-all {@code Content-Security-Policy} with {@code sandbox}: even when a document is
 *       rendered (a top-level SVG, a text file), it has an opaque origin and no script.</li>
 * </ul>
 *
 * <p>PDF is the one exception to the CSP rule: Chromium's PDF viewer is a plugin, and the
 * {@code sandbox} directive (and {@code object-src 'none'}, implied by {@code default-src 'none'})
 * prevents it from loading, so a PDF link would render as a blocked page. A PDF cannot reach the
 * embedding origin's DOM or storage from any mainstream viewer, and {@code nosniff} stops an HTML
 * payload labelled {@code application/pdf} from being sniffed back into HTML.
 */
public final class SafeFileServeHeaders {

    public static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    public static final String NOSNIFF_VALUE = "nosniff";
    public static final String CSP_HEADER = "Content-Security-Policy";

    /**
     * No script, no subresource except the document's own image/media bytes, no plugins,
     * opaque origin. {@code img-src}/{@code media-src 'self'} keep a top-level image or video
     * navigation displaying (the browser's synthetic media document loads the URL itself).
     */
    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox";

    private static final String PDF = "application/pdf";

    /**
     * Types a browser may render as a document without executing anything. Deliberately an
     * allow-list: a deny-list of dangerous types is unwinnable. {@code image/svg+xml} is on it
     * because an {@code <img>} never runs SVG script and a top-level SVG is sandboxed by
     * {@link #CONTENT_SECURITY_POLICY}; forcing it to attachment would break interface SVG icons.
     * {@code text/html}, {@code application/xhtml+xml}, XML (XSLT) and JavaScript stay off.
     */
    private static final Set<String> INLINE_SAFE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp",
            "image/avif", "image/x-icon", "image/vnd.microsoft.icon", "image/svg+xml",
            PDF,
            "text/plain", "text/csv", "text/markdown",
            "application/json",
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-wav",
            "audio/webm", "audio/aac", "audio/flac",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime"
    );

    private SafeFileServeHeaders() {
    }

    /**
     * @param mimeType        the type the file is served with
     * @param requestedInline whether the caller asked for inline rendering
     * @return {@code "inline"} only when requested AND the type is inline-safe, else {@code "attachment"}
     */
    public static String dispositionType(String mimeType, boolean requestedInline) {
        return requestedInline && isInlineSafe(mimeType) ? "inline" : "attachment";
    }

    /** Convenience for the {@code ?disposition=} request parameter (anything but "attachment" asks inline). */
    public static String dispositionType(String mimeType, String requestedDisposition) {
        return dispositionType(mimeType, !"attachment".equalsIgnoreCase(requestedDisposition));
    }

    /** Unknown or absent types are NOT inline-safe. Parameters and case are ignored, as a browser does. */
    public static boolean isInlineSafe(String mimeType) {
        String base = baseType(mimeType);
        return base != null && INLINE_SAFE_TYPES.contains(base);
    }

    /** The CSP for a response of this type, or {@code null} for PDF (see the class javadoc). */
    public static String contentSecurityPolicy(String mimeType) {
        return PDF.equals(baseType(mimeType)) ? null : CONTENT_SECURITY_POLICY;
    }

    /** Adds {@code nosniff} and, unless the type is PDF, the sandboxing CSP to a response. */
    public static <B extends ResponseEntity.HeadersBuilder<B>> B applyTo(B builder, String mimeType) {
        builder.header(NOSNIFF_HEADER, NOSNIFF_VALUE);
        String csp = contentSecurityPolicy(mimeType);
        if (csp != null) {
            builder.header(CSP_HEADER, csp);
        }
        return builder;
    }

    private static String baseType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        int semicolon = mimeType.indexOf(';');
        String base = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
