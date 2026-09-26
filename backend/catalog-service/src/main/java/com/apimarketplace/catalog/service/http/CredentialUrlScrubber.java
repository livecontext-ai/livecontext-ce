package com.apimarketplace.catalog.service.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a credential out of every string that leaves the request itself: log lines, and the
 * error text handed back to the caller (which a workflow stores as step output).
 *
 * <p><b>Why a request URL is not loggable as is.</b> A credential can live IN the URL: a path
 * variable filled from the credential ({@code https://api.telegram.org/bot{token}/sendMessage})
 * or a query-injected key ({@code ?api_key=...}). Stripping the query string, which the retry
 * logs already did, covers only the second. And a transport failure is worse than a log line:
 * RestTemplate words it {@code I/O error on POST request for "<full url>"}, and that message
 * was returned to the caller as the tool's error.
 *
 * <p><b>The rule.</b> Callers keep a {@code safeUrl} built alongside the real one: the URL as it
 * stood BEFORE any credential value entered it, so a credential path variable still reads
 * {@code {token}} and a query-injected key reads {@code key=<redacted>}. Anything worded around
 * the real URL is then rewritten to the safe one, and the raw credential value (plain and
 * URL-encoded) is masked wherever else it appears, which covers a message that renders the URL
 * differently from how it was built. The masked values are every one that entered the URL.
 *
 * <p>That includes values that are not secret (a {@code {subdomain}} such as {@code acme-prod-eu}):
 * the scrubber cannot tell them apart, so it trades a little debuggability for never printing a key.
 */
final class CredentialUrlScrubber {

    static final String REDACTED = "<redacted>";

    /**
     * Below this length a "secret" is more likely a short field value (an id, a region) than a
     * key, and masking every occurrence of it would shred unrelated text. A credential value
     * shorter than this is therefore NEVER masked; only the URL rewrite covers it.
     */
    private static final int MIN_SECRET_LENGTH = 8;

    private CredentialUrlScrubber() {
    }

    /** {@code safeUrl} with the query-injected credential {@code key} appended, value masked. */
    static String withRedactedQueryParam(String safeUrl, String key) {
        return safeUrl + (safeUrl.contains("?") ? "&" : "?") + key + "=" + REDACTED;
    }

    /**
     * {@code text} with the real URL replaced by the safe one and every credential value masked.
     * Null-safe on every argument; a null or empty {@code text} is returned unchanged.
     *
     * <p>The URL is replaced both whole and without its query string, because RestTemplate
     * words a transport failure around the URL MINUS its query. The masking is what actually
     * protects: it does not depend on how a message renders the URL, and {@code secrets} holds
     * every value that entered the URL, not only the primary credential (a Telegram
     * {@code {token}} is read from the credential's data map).
     */
    static String scrub(String text, String realUrl, String safeUrl, Collection<String> secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        if (realUrl != null && safeUrl != null && !realUrl.isEmpty() && !realUrl.equals(safeUrl)) {
            out = out.replace(realUrl, safeUrl);
            String realPath = withoutQuery(realUrl);
            String safePath = withoutQuery(safeUrl);
            if (!realPath.equals(safePath)) {
                out = out.replace(realPath, safePath);
            }
        }
        if (secrets != null) {
            // Longest first, so a secret that contains another is masked whole.
            List<String> ordered = new ArrayList<>();
            for (String s : secrets) {
                if (s != null && s.length() >= MIN_SECRET_LENGTH) {
                    ordered.add(s);
                }
            }
            ordered.sort(Comparator.comparingInt(String::length).reversed());
            for (String secret : ordered) {
                out = out.replace(secret, REDACTED);
                String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
                if (!encoded.equals(secret)) {
                    out = out.replace(encoded, REDACTED);
                }
            }
        }
        return out;
    }

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
