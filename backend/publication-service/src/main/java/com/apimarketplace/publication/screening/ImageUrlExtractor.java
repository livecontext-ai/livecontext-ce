package com.apimarketplace.publication.screening;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure static utility that extracts every media-resource URL referenced
 * by a Template's HTML / CSS / JS - image, video, audio, source, picture,
 * downloadable link with a media extension, CSS background-image.
 *
 * <p>Wave 2a deliberately surfaces ALL referenced resources, including
 * same-origin paths and internal proxy URLs (e.g.
 * {@code /api/files/proxy-signed?…}). Reason: the publisher may have
 * uploaded a third-party image to their own CDN - the URL looks
 * "internal" but the bytes themselves can still be copyright-infringing.
 * The publisher is the only one who can decide per-resource whether they
 * actually hold rights, so we present everything for review.
 *
 * <p>The only exclusions are publisher-controlled inlined bytes that
 * don't fetch from any network or filesystem location:
 * <ul>
 *   <li>{@code data:} URIs (inline base64 bytes)</li>
 *   <li>{@code javascript:}, {@code about:}, {@code mailto:} (not network fetches)</li>
 *   <li>empty / whitespace-only values</li>
 * </ul>
 *
 * <p>Wave 2b will close the dynamic gap (URLs interpolated from
 * {@code items[].data} via {@code ${item.photoUrl}} template literals
 * that this static-source scanner cannot see).
 */
public final class ImageUrlExtractor {

    private ImageUrlExtractor() { }

    // <img src="…">, <video src="…">, <audio src="…"> - any element carrying
    // an src attribute (covers <source> inside <picture>/<video>/<audio>).
    private static final Pattern MEDIA_SRC = Pattern.compile(
            "<(?:img|video|audio|source|iframe|embed)\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    // srcset on <img>/<source> - "<url> <descriptor>, <url> <descriptor>, …"
    private static final Pattern MEDIA_SRCSET = Pattern.compile(
            "<(?:img|source)\\b[^>]*?\\bsrcset\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    // <video poster="…"> - separate attribute from src
    private static final Pattern VIDEO_POSTER = Pattern.compile(
            "<video\\b[^>]*?\\bposter\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    // <a href="…" download> OR <a href="…something.{jpg|png|mp4|…}"> - links
    // that are likely to drag in media bytes when clicked.
    private static final Pattern ANCHOR_DOWNLOAD = Pattern.compile(
            "<a\\b[^>]*?\\b(?:download[^>]*?)?\\bhref\\s*=\\s*[\"']([^\"']+\\.(?:png|jpe?g|gif|webp|avif|svg|ico|mp4|webm|mov|m4v|mp3|ogg|wav|pdf|zip))[\"'](?:[^>]*?\\bdownload\\b)?",
            Pattern.CASE_INSENSITIVE);

    // CSS background / background-image url(…) - unquoted, single- or double-
    // quoted forms.
    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*['\"]?([^'\")\\s]+)['\"]?\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    // Non-network schemes: publisher-controlled inline bytes (data, blob,
    // file) or non-fetch pseudo-URLs (javascript, mailto, tel, sms) - none
    // of these can carry third-party copyrighted content into the published
    // template, so we skip them before the screening surface.
    private static final Pattern NON_NETWORK_SCHEME = Pattern.compile(
            "^(data|blob|file|about|javascript|mailto|tel|sms):", Pattern.CASE_INSENSITIVE);

    /**
     * Scan an HTML template for every media-resource URL it references.
     *
     * @param html raw HTML template (nullable - null returns empty)
     * @return ordered set of deduplicated URLs
     */
    public static Set<String> extractFromHtml(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        addAll(out, MEDIA_SRC.matcher(html), 1);
        addAllSrcset(out, MEDIA_SRCSET.matcher(html));
        addAll(out, VIDEO_POSTER.matcher(html), 1);
        addAll(out, ANCHOR_DOWNLOAD.matcher(html), 1);
        addAll(out, CSS_URL.matcher(html), 1);
        return out;
    }

    /**
     * Scan a CSS template for {@code url(…)} references. Inline
     * {@code <style>} blocks inside htmlTemplate are already captured by
     * {@link #extractFromHtml}; this overload covers the dedicated
     * cssTemplate field on InterfaceEntity.
     */
    public static Set<String> extractFromCss(String css) {
        Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isEmpty()) {
            return out;
        }
        addAll(out, CSS_URL.matcher(css), 1);
        return out;
    }

    /**
     * Scan a JS template for STATIC string literals that look like media
     * URLs. We intentionally do NOT execute or parse the JS - anything
     * dynamic ({@code "${item.photoUrl}"} interpolation, fetch calls,
     * runtime concat) is Wave 2b's responsibility. The literal-string
     * sweep here covers the case where a publisher hardcodes a URL into
     * the script (rare but possible).
     */
    public static Set<String> extractFromJs(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isEmpty()) {
            return out;
        }
        // Quoted string that looks like a media URL (image/video/audio extension).
        // Avoids false positives on every quoted string while catching the
        // realistic "hardcoded photo/video URL" case.
        Pattern jsMediaLiteral = Pattern.compile(
                "[\"'](https?://[^\"'\\s]+\\.(?:png|jpe?g|gif|webp|avif|svg|ico|mp4|webm|mov|m4v|mp3|ogg|wav)(?:\\?[^\"'\\s]*)?)[\"']",
                Pattern.CASE_INSENSITIVE);
        addAll(out, jsMediaLiteral.matcher(js), 1);
        return out;
    }

    private static void addAll(Set<String> sink, Matcher m, int group) {
        while (m.find()) {
            String raw = m.group(group);
            if (raw == null || raw.isBlank()) continue;
            String trimmed = raw.trim();
            if (NON_NETWORK_SCHEME.matcher(trimmed).find()) continue;
            sink.add(trimmed);
        }
    }

    private static void addAllSrcset(Set<String> sink, Matcher m) {
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null) continue;
            for (String candidate : raw.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.isEmpty()) continue;
                int sp = trimmed.indexOf(' ');
                String url = sp > 0 ? trimmed.substring(0, sp) : trimmed;
                if (NON_NETWORK_SCHEME.matcher(url).find()) continue;
                sink.add(url);
            }
        }
    }
}
