package com.apimarketplace.auth.credential.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces the caller-supplied {@code return_url} of an OAuth2 connect to a same-origin relative
 * path (LC-023).
 *
 * <p>The value is concatenated onto {@code oauth2.frontend-url} to form the {@code Location} of the
 * OAuth callback, which lives on the redirect URI registered with every provider. In production the
 * frontend URL is {@code https://livecontext.ai} with no trailing slash, so concatenation extends
 * the HOST: {@code .evil.tld/x} gave {@code livecontext.ai.evil.tld} and {@code @evil.tld/x} gave
 * RFC 3986 userinfo. A single leading {@code /} is what closes that; {@code //} and {@code /\}
 * (protocol-relative forms browsers normalise to a new host) are refused too, as are any scheme,
 * whitespace, control character, backslash or fragment.
 *
 * <p>A same-origin path that is not a page is refused too (CASA, ASVS 5.1.5 defence in depth):
 * the ingress or a frontend rewrite sends {@code /api}, {@code /mcp}, {@code /webhook*},
 * {@code /ws}, {@code /approval-callback}, {@code /chat}, {@code /form}, {@code /c},
 * {@code /share}, {@code /app/public/} and the widget endpoints to backend services (also behind a
 * locale prefix, which the frontend middleware strips with a redirect), and
 * {@code /_next} and {@code /.well-known} only serve framework or protocol files. KEEP IN SYNC with the frontend
 * validator {@code frontend/lib/security/safeReturnPath.ts} ({@code NON_PAGE_PREFIX} and
 * {@code isNonPageTarget}): same prefixes, same normalization.
 */
public final class OAuth2ReturnPath {

    /** Where the callback sends the browser when no usable return path was supplied. */
    public static final String DEFAULT = "/app/settings/credentials";

    /**
     * One leading slash not followed by a second slash or a backslash, then RFC 3986 path and
     * query characters only. No {@code #} (the callback appends a query), no {@code \\}, no
     * whitespace or control characters.
     */
    private static final Pattern SAFE = Pattern.compile("^/(?![/\\\\])[A-Za-z0-9._~!$&'()*+,;=:@%/?-]*$");

    /** Upper bound so a hostile value cannot bloat the state blob or the Location header. */
    private static final int MAX_LENGTH = 2048;

    /**
     * Paths that never name a page (what the prod ingress or a frontend rewrite sends to the
     * gateway, plus framework and protocol files), each matched the way its route really matches:
     * string prefixes {@code /api}, {@code /ws}, {@code /approval-callback}, {@code /webhook},
     * {@code /mcp}, {@code /form} (the ingress compares {@code Prefix} rules as strings and no page
     * starts with these letters); {@code /chat} likewise, bare form included (the cloud ingress
     * takes every {@code /chat*}); {@code /widget/} and {@code /widget.js} exactly as the ingress
     * writes them; whole segments {@code /c}, {@code /share}, {@code /_next},
     * {@code /.well-known}; and {@code /app/public/}. A leading locale does not hide them (see
     * {@link #isNonPagePath}). The rationale for each entry is in the frontend copy.
     * KEEP IN SYNC with {@code NON_PAGE_PREFIX} in {@code frontend/lib/security/safeReturnPath.ts}
     * ({@code safeReturnPath.backendParity.test.ts} there compares the two).
     */
    private static final Pattern NON_PAGE_PREFIX = Pattern.compile(
            "^/(?:api|ws|approval-callback|webhook|mcp|form|chat|widget/|widget\\.js$"
                    + "|(?:c|share|_next|\\.well-known)(?:/|$)|app/public/)");

    /**
     * The frontend's supported locales. KEEP IN SYNC with {@code locales} in
     * {@code frontend/i18n/routing.ts} ({@code safeReturnPath.backendParity.test.ts} compares them).
     */
    private static final List<String> LOCALES = List.of("en", "fr", "es", "de", "pt", "zh");

    /** A leading supported locale segment ({@code /en}, {@code /fr/...}). */
    private static final Pattern LOCALE_SEGMENT =
            Pattern.compile("^/(?:" + String.join("|", LOCALES) + ")(?=/|$)");

    /**
     * Locale-required area that overlaps the deny-list: {@code /<locale>/app/public/...} is a page.
     * KEEP IN SYNC with {@code LOCALE_RENDERED_AREA} in the frontend (parity test compares them).
     */
    private static final Pattern LOCALE_RENDERED_AREA = Pattern.compile("^/app(?:/|$)");

    /** A run of consecutive {@code %XX} escapes. */
    private static final Pattern ESCAPE_RUN = Pattern.compile("(?:%[0-9A-Fa-f]{2})+");

    /** One escape of an ASCII byte (always decodable on its own). */
    private static final Pattern ASCII_ESCAPE = Pattern.compile("%[0-7][0-9A-Fa-f]");

    /** Rounds of percent-decoding a return path may need; more than this is refused outright. */
    private static final int MAX_DECODE_ROUNDS = 3;

    /** C0 controls, DEL and the C1 range, as in the frontend {@code CONTROL_CHARS}. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f]");

    private OAuth2ReturnPath() {
    }

    /** @return {@code returnUrl} when it is a safe same-origin path, else {@link #DEFAULT}. */
    public static String sanitize(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank() || returnUrl.length() > MAX_LENGTH) {
            return DEFAULT;
        }
        if (!SAFE.matcher(returnUrl).matches()) {
            return DEFAULT;
        }
        String path = pathPart(returnUrl);
        if (!hasSafeShape(path)) {
            return DEFAULT;
        }
        List<String> decoded = decodedForms(path);
        if (decoded == null || !decoded.stream().allMatch(OAuth2ReturnPath::hasSafeShape)) {
            return DEFAULT;
        }
        List<String> forms = new ArrayList<>();
        forms.add(path);
        forms.addAll(decoded);
        return isNonPageTarget(forms) ? DEFAULT : returnUrl;
    }

    /**
     * Shape of the path part, raw or decoded: one leading slash, no {@code //} ANYWHERE (a
     * locale-strip redirect turns {@code /en//2130706433} into the protocol-relative
     * {@code //2130706433}), no backslash, no control character.
     * KEEP IN SYNC with {@code hasSafeShape} in {@code frontend/lib/security/safeReturnPath.ts}.
     */
    private static boolean hasSafeShape(String path) {
        return path.startsWith("/")
                && !path.contains("//")
                && !path.contains("\\")
                && !CONTROL_CHARS.matcher(path).find();
    }

    /**
     * Up to {@value #MAX_DECODE_ROUNDS} rounds of (lenient) percent-decoding, stopping at the first
     * stable value; null when the value is STILL changing after the last round (a 4x-encoded
     * {@code /api} would otherwise be judged without ever being seen decoded).
     * KEEP IN SYNC with {@code decodedForms} in {@code frontend/lib/security/safeReturnPath.ts}.
     */
    private static List<String> decodedForms(String value) {
        List<String> forms = new ArrayList<>();
        String current = value;
        for (int round = 0; round < MAX_DECODE_ROUNDS; round++) {
            String next = percentDecode(current);
            if (next.equals(current)) {
                return forms;
            }
            forms.add(next);
            current = next;
        }
        return percentDecode(current).equals(current) ? forms : null;
    }

    /**
     * True when any of {@code forms} (the raw path and its decoded forms) names a non-page target,
     * each one resolved the way a browser would (slashes and backslashes collapsed, dot segments
     * removed) and compared case-insensitively. So {@code /%61pi/x}, {@code /API/}, {@code /./api}
     * and {@code /en/%2e%2e/api} are all caught.
     */
    private static boolean isNonPageTarget(List<String> forms) {
        for (String form : forms) {
            String resolved = resolveDotSegments(pathPart(form).replaceAll("[\\\\/]+", "/"));
            if (isNonPagePath(resolved.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the resolved, lower-cased {@code path} is a non-page target, as is or once its
     * leading locale is stripped. The frontend middleware 308-redirects {@code /<locale><rest>} to
     * {@code <rest>} whenever {@code <rest>} is not a locale-required area, so
     * {@code /en/api/proxy/x} lands on {@code /api/proxy/x} and {@code /en/fr/api} on {@code /api}.
     * A locale-required {@code <rest>} renders under the locale instead, and of those only
     * {@code /app} ({@code /app/public/}) overlaps the deny-list, so the walk stops there.
     */
    private static boolean isNonPagePath(String path) {
        if (NON_PAGE_PREFIX.matcher(path).find()) {
            return true;
        }
        String current = path;
        Matcher locale = LOCALE_SEGMENT.matcher(current);
        while (locale.find()) {
            current = current.substring(locale.end());
            if (current.isEmpty()) {
                current = "/";
            }
            if (LOCALE_RENDERED_AREA.matcher(current).find()) {
                return false;
            }
            if (NON_PAGE_PREFIX.matcher(current).find()) {
                return true;
            }
            locale = LOCALE_SEGMENT.matcher(current);
        }
        return false;
    }

    /** Everything before the first {@code ?} or {@code #}: the part that names the target. */
    private static String pathPart(String value) {
        int end = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) end = query;
        if (fragment >= 0 && fragment < end) end = fragment;
        return value.substring(0, end);
    }

    /** RFC 3986 remove_dot_segments on an absolute path whose slashes are already collapsed. */
    private static String resolveDotSegments(String path) {
        Deque<String> segments = new ArrayDeque<>();
        String[] parts = path.split("/", -1);
        boolean trailingSlash = false;
        for (int i = 1; i < parts.length; i++) {
            String segment = parts[i];
            boolean last = i == parts.length - 1;
            if (segment.equals(".")) {
                trailingSlash = last;
            } else if (segment.equals("..")) {
                segments.pollLast();
                trailingSlash = last;
            } else {
                segments.addLast(segment);
                trailingSlash = false;
            }
        }
        String joined = "/" + String.join("/", segments);
        return trailingSlash && !segments.isEmpty() ? joined + "/" : joined;
    }

    /**
     * One LENIENT round of percent-decoding ({@code +} stays a plus): every run of {@code %XX}
     * escapes is decoded, a malformed {@code %} stays literal, and a run that is not valid UTF-8
     * still has its ASCII escapes decoded. A strict decoder gives up on the whole value instead,
     * which let {@code /%61pi/x%zz} be judged on its raw form only. Never fails.
     * KEEP IN SYNC with {@code lenientDecode} in {@code frontend/lib/security/safeReturnPath.ts}.
     */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        Matcher runs = ESCAPE_RUN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (runs.find()) {
            runs.appendReplacement(out, Matcher.quoteReplacement(decodeRun(runs.group())));
        }
        runs.appendTail(out);
        return out.toString();
    }

    /** Decodes a run of escapes as UTF-8, or only its ASCII escapes when the bytes are not UTF-8. */
    private static String decodeRun(String run) {
        byte[] bytes = new byte[run.length() / 3];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(run.substring(i * 3 + 1, i * 3 + 3), 16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            Matcher ascii = ASCII_ESCAPE.matcher(run);
            StringBuilder out = new StringBuilder();
            while (ascii.find()) {
                char decoded = (char) Integer.parseInt(ascii.group().substring(1), 16);
                ascii.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(decoded)));
            }
            ascii.appendTail(out);
            return out.toString();
        }
    }
}
