package com.apimarketplace.common.web;

import java.util.List;
import java.util.Locale;

/**
 * A request path as a log line may show it: every capability token in it replaced by
 * {@value #MASK}.
 *
 * <p><b>Why.</b> Several routes carry their credential IN the path: webhook tokens
 * ({@code /webhook/<token>}), share links ({@code /share/<token>}, {@code /s/<token>},
 * {@code /c/<token>}), public chat/form/app/widget endpoints, and the internal look-ups that
 * resolve them ({@code .../by-token/<token>}, {@code .../validate/<token>}). The per-request
 * access log wrote the raw path, so on 2026-09-25 Loki held a week of full tokens from the
 * gateway, trigger-service and publication-service: anyone reading logs could fire a webhook or
 * open a shared page. Those tokens are encrypted and hashed at rest precisely so a copy of the
 * data cannot yield them; the access log undid that.
 *
 * <p><b>Two rules, applied together.</b>
 * <ol>
 *   <li>When the servlet stack matched a handler, its pattern names the segment: any path
 *       variable whose name contains {@code token} (e.g. {@code {token}}, {@code {resourceToken}})
 *       is masked. This follows new routes automatically.</li>
 *   <li>For the gateway, which only knows its {@code /webhook/**}-style routes, and for a request
 *       no handler matched, the segment right after a known token prefix is masked.</li>
 * </ol>
 * Everything else in the path (ids, slugs, names) is kept: it is what makes the line useful.
 * The query string is never part of the input (callers log the raw path only).
 */
public final class LogSafePath {

    /** What a token segment reads as in a log line. */
    public static final String MASK = "{token}";

    /** Request attribute where Spring MVC stores the pattern it matched (HandlerMapping). */
    public static final String BEST_MATCHING_PATTERN_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    /**
     * Public routes (gateway and CE monolith) whose FIRST segment after the prefix is the token.
     * Longer prefixes first, so {@code /webhook/agent/} wins over {@code /webhook/}.
     */
    private static final List<String> LEADING_PREFIXES = List.of(
            // The internal forms the gateway rewrites the public routes to: needed when no
            // handler matched (a 404 on an unknown sub-path) and for the MDC, set before dispatch.
            "/api/internal/webhook/agent/",
            "/api/internal/webhook/",
            "/api/internal/app/public/",
            "/api/internal/widget/",
            "/api/internal/chat/",
            "/api/internal/form/",
            "/api/public/share/",
            "/api/shared/c/",
            "/webhook/agent/",
            "/webhook/",
            "/app/public/",
            "/api/ce-link/squat-recovery/",
            "/w/embed/",
            "/widget/",
            "/chat/",
            "/form/",
            "/share/",
            "/c/",
            "/s/",
            "/f/");

    /** Internal look-ups whose NEXT segment is the token, wherever they appear in the path. */
    private static final List<String> INNER_MARKERS = List.of(
            "/by-token/",
            "/by-resource/",
            "/validate/");

    private LogSafePath() {
    }

    /** {@code path} with its token segments masked, using the prefix rules only. */
    public static String of(String path) {
        return of(path, null);
    }

    /**
     * {@code path} with its token segments masked.
     *
     * @param path    the raw request path (no query string); null is returned as is
     * @param pattern the handler pattern the servlet stack matched, or null
     */
    public static String of(String path, String pattern) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String byPattern = byPattern(path, pattern);
        if (byPattern != null) {
            // The matched pattern describes this path segment for segment, so it alone says
            // which segments are tokens: a prefix guess would only add false positives
            // (conversation-service's /api/internal/chat/sync is not a token route).
            return byPattern;
        }
        return byInnerMarker(byLeadingPrefix(path));
    }

    /** The path masked by the pattern, or null when the pattern cannot describe it. */
    private static String byPattern(String path, String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.indexOf('*') >= 0) {
            return null;
        }
        String[] p = pattern.split("/", -1);
        String[] s = path.split("/", -1);
        if (p.length != s.length) {
            return null;
        }
        boolean changed = false;
        for (int i = 0; i < p.length; i++) {
            if (isTokenVariable(p[i])) {
                s[i] = MASK;
                changed = true;
            } else if (!isVariable(p[i]) && !p[i].equals(s[i])) {
                // A pattern that does not describe THIS path (a forward, an error dispatch):
                // masking by position would be a guess. Leave it to the prefix rules.
                return null;
            }
        }
        return changed ? String.join("/", s) : path;
    }

    /** A whole-segment path variable such as {@code {token}} or {@code {shareToken:.+}}. */
    private static boolean isTokenVariable(String segment) {
        if (segment.length() < 3 || segment.charAt(0) != '{' || segment.charAt(segment.length() - 1) != '}') {
            return false;
        }
        String name = segment.substring(1, segment.length() - 1);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        return name.toLowerCase(Locale.ROOT).contains("token");
    }

    private static boolean isVariable(String segment) {
        return segment.length() >= 3 && segment.charAt(0) == '{' && segment.charAt(segment.length() - 1) == '}';
    }

    private static String byLeadingPrefix(String path) {
        for (String prefix : LEADING_PREFIXES) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return prefix + maskFirstSegment(path.substring(prefix.length()));
            }
        }
        return path;
    }

    private static String byInnerMarker(String path) {
        String out = path;
        for (String marker : INNER_MARKERS) {
            int from = 0;
            int i;
            while ((i = out.indexOf(marker, from)) >= 0) {
                int start = i + marker.length();
                if (start >= out.length()) {
                    break;
                }
                out = out.substring(0, start) + maskFirstSegment(out.substring(start));
                from = start + MASK.length();
            }
        }
        return out;
    }

    /**
     * A capability token as a log line may show it: its first {@value #PREVIEW_CHARS} characters
     * (enough to tell a {@code wh_} from an {@code sl_} and to correlate two lines), then
     * {@code ***}. A value too short to preview safely is fully masked; null reads "null".
     */
    public static String tokenPreview(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= PREVIEW_CHARS * 2) {
            return "***";
        }
        return token.substring(0, PREVIEW_CHARS) + "***";
    }

    /**
     * {@code message} with every occurrence of {@code token} replaced by its
     * {@link #tokenPreview preview}. For exception text built around a url that carries the
     * token (RestTemplate words an I/O failure around the path it called).
     */
    public static String withoutToken(String message, String token) {
        if (message == null || token == null || token.isEmpty()) {
            return message;
        }
        return message.replace(token, tokenPreview(token));
    }

    private static final int PREVIEW_CHARS = 6;

    /** {@code rest} with its first segment replaced by the mask (nothing to mask if it is empty). */
    private static String maskFirstSegment(String rest) {
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        if (first.isEmpty() || first.equals(MASK)) {
            return rest;
        }
        return slash < 0 ? MASK : MASK + rest.substring(slash);
    }
}
