package com.apimarketplace.common.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loggable (and caller-returnable) forms of URLs and of messages that quote URLs (LC-010).
 *
 * <p>An outbound URL carries user data in its query (a Gmail search expression) and, for query-
 * or userinfo-injected credentials, the credential itself. Client libraries repeat the full URL in
 * their exception messages ({@code I/O error on GET request for "https://...?key=..."}), so a
 * plain {@code e.getMessage()} in a log line leaks it. Every URL of ANY scheme loses its userinfo,
 * query and fragment here, and the text is capped because a provider error body can echo the
 * request.
 */
public final class UrlLogRedaction {

    /** Characters of a message kept in a log line. */
    public static final int MESSAGE_CAP = 300;

    /**
     * {@code scheme://[userinfo@]authority[path][?query][#fragment]} for any RFC 3986 scheme
     * (http, https, ftp, jdbc:postgresql, redis, ws, ...). Group 1 = scheme, 2 = userinfo,
     * 3 = authority + path, 4 = query/fragment.
     */
    private static final Pattern URL = Pattern.compile(
            "([A-Za-z][A-Za-z0-9+.\\-]*(?::[A-Za-z][A-Za-z0-9+.\\-]*)*://)"
                    + "([^\\s\"'<>/@?#]*@)?"
                    + "([^\\s\"'<>?#]*)"
                    + "([?#][^\\s\"'<>]*)?");

    private UrlLogRedaction() {
    }

    /**
     * A message with every URL's userinfo, query and fragment removed, capped. Null-safe.
     *
     * <p>The authority and PATH are deliberately kept (group 3 below), not redacted: a credential
     * or token lives in userinfo or a query parameter, essentially never in a path segment, and the
     * path is exactly what a reader needs to diagnose which endpoint failed ({@code /users/{id}}
     * vs {@code /users/{id}/permissions}). Redacting it too would trade real diagnostic value for
     * no additional safety (CASA readiness round 3 - this is a decision, not an oversight).
     */
    public static String redact(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = URL.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        while (m.find()) {
            String replacement = m.group(1)
                    + (m.group(2) != null ? "<redacted>@" : "")
                    + m.group(3)
                    + (m.group(4) != null ? "?<redacted>" : "");
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String redacted = out.toString();
        return redacted.length() <= MESSAGE_CAP
                ? redacted
                : redacted.substring(0, MESSAGE_CAP) + "...(" + redacted.length() + " chars)";
    }

    /** {@code scheme://host[:port]} of a URL, never its path, query or userinfo. */
    public static String origin(String url) {
        if (url == null) {
            return "<none>";
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (uri.getHost() == null) {
                return "<no host>";
            }
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://") + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "<unparseable url>";
        }
    }
}
