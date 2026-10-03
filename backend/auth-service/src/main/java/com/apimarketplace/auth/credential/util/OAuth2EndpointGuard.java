package com.apimarketplace.auth.credential.util;

import com.apimarketplace.common.web.UrlResolutionException;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The check applied to an OAuth2 endpoint URL that a USER controls (LC-052).
 *
 * <p>{@code POST /api/platform-credentials/my} is user-accessible and persisted {@code authUrl} /
 * {@code tokenUrl} verbatim; those values then reached an outbound POST carrying the client
 * secret, the authorization code and the refresh token. Pointing {@code tokenUrl} at an internal
 * address turned the token exchange into a request-forgery primitive against the cluster.
 *
 * <p>Two strengths, same rules (https, then {@link UrlSafetyValidator#validateUrl}: no localhost,
 * loopback, private, link-local or any-local address):
 * <ul>
 *   <li><strong>Save time</strong> ({@link #assertSafe}): a host that cannot be RESOLVED right
 *       now, or that still holds a per-instance {@code {placeholder}}, is accepted on the format
 *       checks. Saving must not depend on DNS being healthy at that instant, and a templated host
 *       cannot be resolved before the user supplies its value.</li>
 *   <li><strong>Use time</strong> ({@link #assertSafeForUse}, {@link #assertPublicHostForUse}),
 *       right before a credential-bearing request: the host MUST resolve, every address must be
 *       public, and no placeholder may remain. Nothing tolerated at save time survives to here.</li>
 * </ul>
 *
 * <p>Self-hosted consequence: a CE install cannot use a BYOK OAuth provider served on a private
 * address (an identity provider on the LAN, {@code 192.168.x.x}, {@code *.internal}); the connect
 * is refused at save and at use. Honouring the CE egress allow-list here is the follow-up once
 * {@code UrlSafetyValidator} exposes it.
 *
 * <p>Known limit: the addresses are resolved here and again by the HTTP client (no pinning), so a
 * DNS answer that changes between the two lookups is not caught. That is the platform-wide
 * rebinding gap tracked separately (LC-073), not specific to OAuth.
 */
public final class OAuth2EndpointGuard {

    private static final Logger log = LoggerFactory.getLogger(OAuth2EndpointGuard.class);

    private OAuth2EndpointGuard() {
    }

    /**
     * Save-time check. {@code null}/blank is accepted as "not configured".
     *
     * @throws IllegalArgumentException when the URL is not an https endpoint on a public host
     */
    public static void assertSafe(String url, String field) {
        if (url == null || url.isBlank()) {
            return;
        }
        String trimmed = requireHttps(url, field);
        try {
            UrlSafetyValidator.validateRegistrationUrl(trimmed);
        } catch (UrlResolutionException unavailable) {
            log.warn("Could not resolve the host of {} right now; accepted on the format checks "
                    + "(re-checked with resolution before every use)", field);
        } catch (IllegalArgumentException unsafe) {
            String message = unsafe.getMessage();
            if (message != null && message.startsWith("Cannot resolve hostname")) {
                log.warn("Host of {} does not resolve; accepted on the format checks "
                        + "(re-checked with resolution before every use)", field);
                return;
            }
            throw new IllegalArgumentException(field + " is not a valid OAuth2 endpoint: " + message);
        }
    }

    /** Use-time check of a user-controlled endpoint: https, resolvable, public, no placeholder. */
    public static void assertSafeForUse(String url, String field) {
        assertPublicHostForUse(requireHttps(url, field), field);
    }

    /**
     * Use-time check without the https rule, for a CATALOG endpoint whose host was filled from
     * user input (per-instance {@code {host}} vars): the scheme is the template's, the host is the
     * user's. Fails closed on an unresolvable host or a leftover placeholder.
     */
    public static void assertPublicHostForUse(String url, String field) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(field + " is not configured");
        }
        String trimmed = url.trim();
        if (trimmed.indexOf('{') >= 0 || trimmed.indexOf('}') >= 0) {
            throw new IllegalArgumentException(field + " still holds an unresolved placeholder");
        }
        try {
            UrlSafetyValidator.validateUrl(trimmed);
        } catch (IllegalArgumentException unsafe) {
            throw new IllegalArgumentException(field + " is not a valid OAuth2 endpoint: " + unsafe.getMessage());
        }
    }

    private static String requireHttps(String url, String field) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(field + " is not configured");
        }
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(field + " must be an https URL");
        }
        return trimmed;
    }
}
