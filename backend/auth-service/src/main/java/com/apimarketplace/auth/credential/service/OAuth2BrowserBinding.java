package com.apimarketplace.auth.credential.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Ties an OAuth2 authorization flow to the browser that started it (LC-005).
 *
 * <p>The {@code state} parameter alone does not do this: it is a Redis key, and whoever presents
 * it at the public callback gets the flow completed under the identity stored in that blob. An
 * attacker could call {@code /initiate} on their own account, send the resulting GENUINE provider
 * consent URL to a victim, and have the victim's refresh token persisted under the attacker's
 * tenant (OAuth account-linking CSRF, RFC 9700 4.4.1.1, ASVS V51.2).
 *
 * <p>The fix is a second secret that only the initiating browser holds: a 32-byte CSPRNG value set
 * as a cookie on the initiate response, whose SHA-256 is stored in the state blob. The callback
 * recomputes the hash from the cookie the browser presents and compares in constant time. Storing
 * the hash rather than the value keeps the secret out of Redis.
 *
 * <p><strong>Topology.</strong> The initiate call reaches auth-service through the Next.js proxy
 * ({@code <app>/api/proxy/...}), which relays {@code Set-Cookie}; the callback is a top-level GET
 * navigation from the provider to the configured callback URL. The cookie is host-only with
 * {@code Path=/}, so it reaches the callback whenever the app and the callback share a host
 * (cloud: both {@code livecontext.ai}; CE: {@code localhost:3000} and {@code localhost:8080},
 * cookies ignore the port). {@code SameSite=Lax} is sent on that top-level navigation.
 *
 * <p><strong>One cookie per flow.</strong> The name carries a short one-way reference of the
 * state, so two connects started in two tabs do not overwrite each other's binding.
 */
public final class OAuth2BrowserBinding {

    /** Cookie name prefix when the deployment is served over https ({@code __Host-} rules). */
    static final String SECURE_PREFIX = "__Host-lc_oauth_";
    /** Cookie name prefix for plain-http installs (local dev, CE on a LAN address). */
    static final String PLAIN_PREFIX = "lc_oauth_";

    /** Long enough for a slow consent screen, longer than the 10 min state TTL. */
    public static final int COOKIE_MAX_AGE_SECONDS = 15 * 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth2BrowserBinding() {
    }

    /** @return a fresh 32-byte value, base64url-encoded, to hand to the initiating browser. */
    public static String newValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** @return the SHA-256 of {@code value}, base64url-encoded, or null when value is blank. */
    public static String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(value));
    }

    /**
     * @param expectedHash   the hash carried by the state blob
     * @param presentedValue the raw cookie value the callback received
     * @return true only when the browser presented the value this flow was bound to. Fails
     *         CLOSED on a blob without a hash: treating "no binding" as "no check" would leave
     *         the hole permanently reachable by anyone able to write a state blob.
     */
    public static boolean matches(String expectedHash, String presentedValue) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        String presentedHash = hash(presentedValue);
        if (presentedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                presentedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether the binding cookie can be {@code Secure} ({@code __Host-} prefixed). Both ends must
     * be https: the cookie is SET by the app origin (initiate goes through the Next proxy) and
     * READ on the callback origin, and a browser drops a Secure cookie set over http.
     */
    public static boolean secureDeployment(String frontendUrl, String callbackUrl) {
        return isHttps(frontendUrl) && isHttps(callbackUrl);
    }

    /** Cookie name for the flow identified by {@code state}. */
    public static String cookieName(String state, boolean secure) {
        return (secure ? SECURE_PREFIX : PLAIN_PREFIX) + OAuth2StateRef.of(state);
    }

    /** {@code Set-Cookie} header value that hands {@code value} to the initiating browser. */
    public static String setCookieHeader(String state, String value, boolean secure) {
        return cookieHeader(cookieName(state, secure), value, COOKIE_MAX_AGE_SECONDS, secure);
    }

    /** {@code Set-Cookie} header value that removes the flow's cookie once the callback ran. */
    public static String clearCookieHeader(String state, boolean secure) {
        return cookieHeader(cookieName(state, secure), "", 0, secure);
    }

    private static String cookieHeader(String name, String value, int maxAge, boolean secure) {
        // Host-only (no Domain) and Path=/ are both required by the __Host- prefix, and Path=/
        // is also what lets the cookie set on /api/proxy/... reach /api/credentials/oauth2/callback.
        StringBuilder cookie = new StringBuilder()
                .append(name).append('=').append(value)
                .append("; Path=/")
                .append("; Max-Age=").append(maxAge)
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private static boolean isHttps(String url) {
        return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
