package com.apimarketplace.common.storage.url;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Recognises the two URL shapes the platform hands out for a stored file and reads back
 * what they point at.
 *
 * <ul>
 *   <li>{@code /api/files/proxy?key=…} - the authenticated proxy. Baked into interface data
 *       by the interface renderer / FormDispatchService when a template renders a file
 *       reference as an already-resolved URL. Carries no proof of anything.</li>
 *   <li>{@code /api/files/proxy-signed?key=…&exp=…&disposition=…&sig=…} - the public
 *       HMAC-signed proxy minted by the {@code core:public_link} node (absolute, with the
 *       install's public origin) and by the marketplace showcase rewriter (relative).</li>
 * </ul>
 *
 * <p>Both are a file reference that happens to have been flattened into a String. Every
 * consumer that walks a data tree looking for files ({@code _type:"file"} maps) is blind to
 * them, which is how a {@code core:public_link} URL frozen into a publication snapshot
 * survived both the publish-time copy into the publication's storage namespace and the
 * read-time re-signing, then 403'd the moment its {@code exp} passed. This class is the
 * single place that knows the shapes, so a new consumer does not re-derive them (and does
 * not re-derive them slightly differently).
 *
 * <p><strong>Parsing only. This class establishes no trust.</strong> It will happily read a
 * key out of a URL pointing at another host, carrying a forged signature, naming a file the
 * reader has no right to. Deciding whether a parsed URL may be acted on belongs to the
 * caller, which needs the signing key to answer it. Anything that turns a parsed key into a
 * file access must gate on that first.
 *
 * <p>Deliberately strict about what counts as a URL: only a value that is <em>entirely</em>
 * one is recognised. A sentence that merely mentions one is left alone, because rewriting it
 * would replace the prose with a bare link.
 */
public final class FileProxyUrls {

    /** Substring present in both shapes; a cheap pre-filter before parsing. */
    public static final String PATH_MARKER = "/api/files/proxy";

    private static final String SIGNED_PATH = "/api/files/proxy-signed?";
    private static final String PROXY_PATH = "/api/files/proxy?";
    private static final String DEFAULT_DISPOSITION = "inline";

    private FileProxyUrls() {
    }

    /**
     * A parsed file-proxy URL.
     *
     * @param key         the decoded storage key
     * @param signed      true for the {@code proxy-signed} endpoint
     * @param absolute    true when the value carried a scheme or an authority, so the caller
     *                    can tell "our own relative URL" from "a URL naming some host"
     * @param exp         expiry epoch seconds, 0 when absent
     * @param disposition {@code inline} when absent, mirroring the endpoint's own default
     * @param sig         the decoded signature, null when absent
     */
    public record ProxyUrl(String key, boolean signed, boolean absolute,
                           long exp, String disposition, String sig) {
    }

    /**
     * @return the parsed URL, or {@code null} when {@code value} is not entirely a
     *         file-proxy URL
     */
    public static ProxyUrl parse(String value) {
        if (value == null) return null;
        // Trim first: a value a workflow wrote into a text field can carry a trailing newline
        // and is still entirely a URL. Refusing it would leave that reference uncopied and
        // un-re-signed, which is the very 403 this class exists to prevent.
        value = value.strip();
        if (value.isEmpty()) return null;
        // INNER whitespace, though, means prose quoting a URL rather than a URL.
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return null;
        }
        boolean absolute = !value.startsWith("/") || value.startsWith("//");
        if (absolute && !value.startsWith("//")
                && !value.regionMatches(true, 0, "http://", 0, 7)
                && !value.regionMatches(true, 0, "https://", 0, 8)) {
            // Some other scheme entirely; nothing here is one of our URLs. Matched in full
            // rather than on the first four characters, which also accepted httpx:// and a
            // bare relative path starting with the letters http.
            return null;
        }
        // A fragment is not part of the query. Cutting it off keeps `#frag` out of the key,
        // which would otherwise name a file that exists nowhere.
        int fragment = value.indexOf('#');
        String url = fragment >= 0 ? value.substring(0, fragment) : value;

        // Locate the endpoint anywhere in the path rather than immediately after the
        // authority: an install served under a path prefix (app.public-url =
        // https://host/lc) mints https://host/lc/api/files/proxy-signed?… and anchoring on
        // the authority would silently stop recognising its own links.
        boolean signed = true;
        int at = url.lastIndexOf(SIGNED_PATH);
        if (at < 0) {
            signed = false;
            at = url.lastIndexOf(PROXY_PATH);
        }
        if (at < 0) return null;
        // The endpoint must be in the PATH. A `?` before it means we matched inside somebody
        // else's query string (…/redirect?to=/api/files/proxy?key=…), which is not our URL.
        if (url.lastIndexOf('?', at) >= 0) return null;

        String query = url.substring(at + (signed ? SIGNED_PATH.length() : PROXY_PATH.length()));
        String key = param(query, "key");
        if (key == null || key.isBlank()) return null;
        String disposition = param(query, "disposition");
        String sig = param(query, "sig");
        long exp = parseEpoch(param(query, "exp"));
        return new ProxyUrl(key, signed, absolute, exp,
                disposition == null || disposition.isBlank() ? DEFAULT_DISPOSITION : disposition, sig);
    }

    /**
     * The relative {@code /api/files/proxy-signed} URL for an already computed signature, in
     * the exact shape {@link #parse} reads back. Building the string is all this does: the
     * caller signs with {@code ShowcaseUrlSigner} and owns the decision that the key may be
     * served at all.
     */
    public static String signedPath(String key, long exp, String disposition, String sig) {
        return SIGNED_PATH
                + "key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&exp=" + exp
                + "&disposition=" + ("attachment".equalsIgnoreCase(disposition) ? "attachment" : DEFAULT_DISPOSITION)
                + "&sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8);
    }

    /**
     * The storage key a value points at, without any trust decision. Prefer
     * {@link #parse} when the caller needs to weigh provenance, which it usually does.
     */
    public static String storageKeyOf(String value) {
        ProxyUrl parsed = parse(value);
        return parsed == null ? null : parsed.key();
    }

    /**
     * Read one query parameter.
     *
     * <p>Split on {@code &} rather than searching for {@code name=} anywhere: a substring
     * search also matches the tail of another parameter name ({@code &sortKey=}) and would
     * return that value. First occurrence wins, which is what
     * {@code ServletRequest.getParameter} gives the endpoint on the other side, so a
     * repeated parameter is read the same way here and there.
     *
     * @return the decoded value, or {@code null} when absent or malformed
     */
    private static String param(String query, String name) {
        String prefix = name + "=";
        for (String pair : query.split("&")) {
            if (!pair.startsWith(prefix)) continue;
            try {
                return URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                // A malformed escape (key=%zz) decodes to nothing usable. Returning the raw
                // text would hand a caller a key that names no file and fails downstream
                // with a confusing error instead of here, where the input is at hand.
                return null;
            }
        }
        return null;
    }

    private static long parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
