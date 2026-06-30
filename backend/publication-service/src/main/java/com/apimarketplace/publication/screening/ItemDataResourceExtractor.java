package com.apimarketplace.publication.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wave 2b - closes the gap left open by {@link ImageUrlExtractor}.
 *
 * <p>{@link ImageUrlExtractor} only sees the <em>static</em> interface
 * template (HTML/CSS/JS), where image references are almost always
 * {@code {{placeholder}}} tokens - so scraped/downloaded images are
 * invisible to it. The real images are resolved into the per-item render
 * data ({@code items[].data}) at run time: a third-party CDN URL
 * interpolated via {@code variableMapping} (e.g. {@code displayUrl ->
 * scontent-*.cdninstagram.com/...jpg}), or a downloaded FileRef the
 * publisher re-hosts ({@code download_file} / {@code image_generation}).
 *
 * <p>This extractor walks {@code items[].data} and surfaces both classes so
 * the pre-publish screening can flag them just like template images:
 * <ul>
 *   <li><b>FileRef objects</b> ({@code {_type:"file", path, mimeType}}) with
 *       an image/video/audio mime (or a media extension when the mime is
 *       missing/opaque) - emitted as their {@code path};</li>
 *   <li><b>http(s) string URLs</b> that are image-like - either the URL path
 *       carries a media extension, or the data key under which the URL sits
 *       names an image ({@code image}, {@code photo}, {@code avatar},
 *       {@code displayUrl}, {@code thumbnail}, …).</li>
 * </ul>
 *
 * <p>Plain link fields (a post permalink under {@code url}, a bio website
 * under {@code externalUrl}) are intentionally NOT flagged: they are not
 * images and carry no copyright-display risk. The heuristic errs toward
 * catching (an over-flag is one extra row the publisher can dismiss; a
 * miss ships a third-party image unscreened) but stays anchored to
 * extension/key/mime so it does not flag every URL in the data.
 *
 * <p>Scope mirrors {@code ShowcaseFileRefRewriter}: only {@code items[].data}
 * is walked, never {@code triggerData} (which can carry an acquirer's
 * cross-tenant uploads).
 */
@Service
public class ItemDataResourceExtractor {

    private final ObjectMapper objectMapper;

    public ItemDataResourceExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** http(s) scheme prefix. */
    private static final Pattern HTTP = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    /** A media file extension at the end of the URL/path part (query/fragment stripped). */
    private static final Pattern MEDIA_EXT = Pattern.compile(
            "\\.(png|jpe?g|gif|webp|avif|svg|ico|bmp|tiff?|heic|heif|mp4|webm|mov|m4v|mp3|ogg|wav|m4a)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Data-key tokens that name an image/media value. Matched as a
     * case-insensitive substring of the key - short, ambiguous tokens
     * ({@code pic}) are deliberately omitted to avoid matching keys like
     * {@code topics}; the URL still must be http(s) for any of this to fire.
     */
    private static final Pattern MEDIA_KEY = Pattern.compile(
            "image|img|photo|picture|avatar|thumbnail|thumb|banner|icon|media|cover|poster"
                    + "|displayurl|profilepic|screenshot|gravatar|artwork|wallpaper"
                    // `logo` only when it is the whole token or precedes url/src/image/s -
                    // avoids matching unrelated keys like `logoutUrl`.
                    + "|logo(?=url|src|image|s|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extract every image-like resource referenced by the resolved render
     * items. Returns an ordered, deduplicated set of identifiers: a FileRef
     * {@code path} or a raw http(s) URL.
     *
     * @param items the render result's {@code items} list (each entry a map
     *              with a {@code data} sub-map); nullable
     */
    public Set<String> extract(List<? extends Map<String, Object>> items) {
        Set<String> out = new LinkedHashSet<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        for (Map<String, Object> item : items) {
            if (item == null) continue;
            walk(null, item.get("data"), out);
        }
        return out;
    }

    private void walk(String key, Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            if (isImageFileRef(map)) {
                out.add(String.valueOf(map.get("path")));
                return; // FileRef is a leaf - don't descend into its fields
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                walk(String.valueOf(e.getKey()), e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            // Array elements inherit the parent key (e.g. a `images: [url, url]`
            // string array stays attributed to the `images` key).
            for (Object item : list) {
                walk(key, item, out);
            }
        } else if (node instanceof String s) {
            String trimmed = s.trim();
            if (isImageUrl(key, trimmed)) {
                out.add(trimmed);
            } else if (looksLikeJson(trimmed)) {
                // A JSON-encoded blob (e.g. a `postsJson` string produced by a
                // js_template) can itself embed FileRefs / image URLs. Parse and
                // recurse so they are not missed. Mirrors ShowcaseFileRefRewriter.
                parseAndWalk(trimmed, out);
            }
        }
    }

    private void parseAndWalk(String json, Set<String> out) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            // Re-walk under no key; nested maps/lists carry their own keys.
            walk(null, parsed, out);
        } catch (Exception ignored) {
            // Not valid JSON - nothing to extract.
        }
    }

    private static boolean looksLikeJson(String s) {
        if (s.length() < 2) return false;
        char c = s.charAt(0);
        return (c == '[' || c == '{') && (s.contains("\"_type\"") || s.contains("http"));
    }

    private static boolean isImageFileRef(Map<?, ?> m) {
        if (!"file".equals(m.get("_type"))) return false;
        if (!(m.get("path") instanceof String path) || path.isBlank()) return false;
        return isMediaMime(m.get("mimeType"), path);
    }

    private static boolean isMediaMime(Object mime, String path) {
        if (mime instanceof String s) {
            String lower = s.toLowerCase();
            if (lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")) {
                return true;
            }
            // Opaque mime (download_file often stores application/octet-stream)
            // → fall back to the path extension.
            if (lower.equals("application/octet-stream") || lower.isBlank()) {
                return hasMediaExtension(path);
            }
            return false;
        }
        // No mime recorded → infer from the path extension.
        return hasMediaExtension(path);
    }

    private static boolean isImageUrl(String key, String value) {
        if (!HTTP.matcher(value).find()) return false;
        if (hasMediaExtension(value)) return true;
        return key != null && MEDIA_KEY.matcher(key).find();
    }

    /** True when the path part (query/fragment stripped) ends with a media extension. */
    private static boolean hasMediaExtension(String urlOrPath) {
        String p = urlOrPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        return MEDIA_EXT.matcher(p).find();
    }
}
