package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig;
import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig.TokenExchangeConfig.RequestFormat;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2TokenResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, stateless OAuth2 mechanics. Given an {@link OAuth2ProviderConfig}, the engine knows how
 * to build an authorization URL, assemble a token exchange / refresh request, and parse a token
 * response - regardless of which provider (Google, Airtable, Microsoft, Notion, …) is on the
 * other side.
 *
 * <p>The engine holds no state and no Spring-managed dependencies beyond the bean marker, so
 * every method is trivially unit-testable without mocks. It is the single place that understands
 * the OAuth2 wire protocol; {@link OAuth2Service} is just an orchestrator that wires it to
 * Redis, the catalog, and the credential store.
 *
 * <p><strong>Provider quirks are expressed as config, not code.</strong> If a provider needs
 * {@code access_type=offline} (Google), {@code audience=…} (Atlassian), or a tenant template,
 * that goes in {@code authorizeExtraParams}. Non-RFC fields that come back in a token response
 * (Salesforce {@code instance_url}, Xero {@code tenant_id}, QuickBooks {@code realmId}, Zoho
 * {@code region}) are declared via {@link OAuth2ProviderConfig.RefreshQuirks} and harvested here
 * by {@link #extractQuirkFields}.
 */
@Component
public class OAuth2Engine {

    /**
     * Placeholder value an {@code authorizeExtraParams} entry uses to request the consent screen's
     * UI language. At authorize time the engine substitutes it with the app's current locale
     * (the next-intl locale the user is browsing in) so the provider renders its consent + scope
     * descriptions in that language. Google honours {@code hl=<locale>}; a provider's JSON declares
     * the param NAME (Google {@code "hl"}, OIDC providers {@code "ui_locales"}) and this fixed
     * placeholder as the VALUE, e.g. {@code "authorizeExtraParams": { "hl": "{ui_locale}" }}.
     *
     * <p>When the caller has no locale, the placeholder entry is dropped entirely - the URL stays
     * byte-identical to the pre-feature behaviour, so the provider falls back to the account /
     * browser language exactly as before.
     */
    public static final String UI_LOCALE_PLACEHOLDER = "{ui_locale}";

    /**
     * User-Agent sent on every token request (code exchange, refresh, client_credentials).
     * Providers that bucket rate limits by agent, most notably Reddit's
     * {@code www.reddit.com/api/v1/access_token}, return {@code 429 Too Many Requests} when the
     * request carries the HTTP client's default agent (shared across every bot). A single
     * descriptive agent is accepted by every provider, so it is set unconditionally rather than
     * gated on a provider name (keeping with "provider quirks are config, not code").
     */
    static final String TOKEN_REQUEST_USER_AGENT = "LiveContext/1.0 (+https://livecontext.ai)";

    public record TokenRequest(
            String url,
            HttpEntity<?> entity
    ) {}

    /**
     * Build the provider authorization URL (the one the user is redirected to). Adds all
     * standard params (response_type, client_id, redirect_uri, state, scope), any
     * provider-specific extras from {@link OAuth2ProviderConfig#authorizeExtraParams()}, and the
     * PKCE challenge when enabled.
     *
     * <p>Locale-unaware overload, kept for callers that do not have an app locale to forward (and
     * for backward compatibility with existing tests). Delegates with {@code uiLocale = null},
     * which drops any {@link #UI_LOCALE_PLACEHOLDER} param so the output is unchanged.
     *
     * @param pkceChallenge PKCE challenge pair, or {@code null} if PKCE is not enabled
     */
    public String buildAuthorizationUrl(
            OAuth2ProviderConfig config,
            String clientId,
            String state,
            String callbackUrl,
            PkceService.PkceChallenge pkceChallenge
    ) {
        return buildAuthorizationUrl(config, clientId, state, callbackUrl, pkceChallenge, null);
    }

    /**
     * Build the provider authorization URL, rendering the consent screen in {@code uiLocale} when
     * the provider declares a {@link #UI_LOCALE_PLACEHOLDER} param (see the placeholder Javadoc).
     *
     * @param pkceChallenge PKCE challenge pair, or {@code null} if PKCE is not enabled
     * @param uiLocale      app UI locale (e.g. {@code "fr"}, {@code "en"}); {@code null}/blank drops
     *                      the locale param so behaviour matches the locale-unaware overload
     */
    public String buildAuthorizationUrl(
            OAuth2ProviderConfig config,
            String clientId,
            String state,
            String callbackUrl,
            PkceService.PkceChallenge pkceChallenge,
            String uiLocale
    ) {
        StringBuilder url = new StringBuilder(config.authorizationUrl());
        url.append(config.authorizationUrl().contains("?") ? '&' : '?');
        appendParam(url, "response_type", "code", false);
        appendParam(url, config.clientIdParam(), clientId, true);
        appendParam(url, "redirect_uri", callbackUrl, true);
        appendParam(url, "state", state, true);

        String scope = config.joinedScopes();
        if (!scope.isEmpty()) {
            appendParam(url, "scope", scope, true);
        }

        boolean hasLocale = uiLocale != null && !uiLocale.isBlank();

        // Provider-specific extras declared in JSON. This is the generic, data-driven mechanism
        // that lets any provider inject whatever it needs without a code change.
        for (Map.Entry<String, String> e : config.authorizeExtraParams().entrySet()) {
            if (UI_LOCALE_PLACEHOLDER.equals(e.getValue())) {
                // Dynamic UI-locale param: substitute the app locale, or drop the param when the
                // caller has none (keeps the URL identical to the pre-locale behaviour).
                if (hasLocale) {
                    appendParam(url, e.getKey(), uiLocale, true);
                }
                continue;
            }
            appendParam(url, e.getKey(), e.getValue(), true);
        }

        if (pkceChallenge != null) {
            appendParam(url, "code_challenge", pkceChallenge.challenge(), true);
            appendParam(url, "code_challenge_method", pkceChallenge.method(), true);
        }

        return url.toString();
    }

    /**
     * Assemble the POST body and headers for the token exchange request (authorization_code
     * grant). Client credentials are placed in the body or HTTP Basic header according to
     * {@link OAuth2ProviderConfig#tokenAuthMethod()}. {@code codeVerifier} is added when PKCE
     * was used at authorize time.
     */
    public HttpEntity<?> buildTokenExchangeRequest(
            OAuth2ProviderConfig config,
            String code,
            String clientId,
            String clientSecret,
            String callbackUrl,
            String codeVerifier
    ) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add(config.tokenExchange().codeParam(), code);
        body.add("redirect_uri", callbackUrl);

        if (codeVerifier != null) {
            body.add("code_verifier", codeVerifier);
        }

        return finalizeRequest(config, body, clientId, clientSecret);
    }

    /**
     * Assemble the POST body and headers for a refresh_token grant request.
     */
    public HttpEntity<?> buildRefreshRequest(
            OAuth2ProviderConfig config,
            String refreshToken,
            String clientId,
            String clientSecret
    ) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        return finalizeRequest(config, body, clientId, clientSecret);
    }

    /**
     * Assemble the request body for a client_credentials token request.
     */
    public HttpEntity<?> buildClientCredentialsRequest(
            OAuth2ProviderConfig config,
            String clientId,
            String clientSecret
    ) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        return finalizeRequest(config, body, clientId, clientSecret);
    }

    public TokenRequest materializeTokenRequest(
            OAuth2ProviderConfig config,
            String tokenUrl,
            HttpEntity<?> request
    ) {
        if (config.tokenParamsLocation() != OAuth2ProviderConfig.TokenParamsLocation.QUERY) {
            return new TokenRequest(tokenUrl, request);
        }

        // QUERY location only applies to the form-encoded body path (never JSON), so the body is a
        // MultiValueMap here. tokenParamsLocation=QUERY and tokenRequestFormat=JSON is not a valid
        // combination for any provider.
        @SuppressWarnings("unchecked")
        MultiValueMap<String, String> body = (MultiValueMap<String, String>) request.getBody();
        String resolvedUrl = appendQueryParams(tokenUrl, body);
        HttpEntity<MultiValueMap<String, String>> entityWithoutBody =
                new HttpEntity<>(new LinkedMultiValueMap<>(), request.getHeaders());
        return new TokenRequest(resolvedUrl, entityWithoutBody);
    }

    /**
     * Parse a token endpoint JSON response into a typed {@link OAuth2TokenResponse}. Only
     * handles the standard RFC 6749 shape; providers that wrap tokens in a different JSON
     * structure (Slack's {@code authed_user.access_token}, Salesforce's {@code instance_url},
     * …) will be handled in PR 2 via {@code tokenResponseMapping}.
     */
    public OAuth2TokenResponse parseTokenResponse(JsonNode body, OAuth2ProviderConfig config) {
        String path = config.tokenExchange().responsePath();
        if (path == null) {
            return parseTokenResponse(body);
        }
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw new IllegalStateException("Token endpoint returned empty body");
        }
        // Non-RFC providers nest the token under a wrapper object (TikTok for Business: "data").
        JsonNode nested = body.path(path);
        if (nested.isMissingNode() || nested.isNull()) {
            throw new IllegalStateException(
                    "Token response missing '" + path + "' wrapper: " + body.toString());
        }
        return parseTokenResponse(nested);
    }

    public OAuth2TokenResponse parseTokenResponse(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            throw new IllegalStateException("Token endpoint returned empty body");
        }

        String accessToken = body.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "Token response missing access_token: " + body.toString());
        }

        return new OAuth2TokenResponse(
                accessToken,
                textOrNull(body, "refresh_token"),
                body.path("token_type").asText("Bearer"),
                body.has("expires_in") && !body.path("expires_in").isNull()
                        ? body.path("expires_in").asLong()
                        : null,
                textOrNull(body, "scope")
        );
    }

    /** Whether the engine should issue a PKCE challenge for this provider. */
    public boolean shouldUsePkce(OAuth2ProviderConfig config) {
        return config.pkceEnabled();
    }

    /**
     * Harvest non-RFC-6749 fields from a token response according to the provider's declared
     * quirks (see {@link OAuth2ProviderConfig.RefreshQuirks}). Returns a map ready to be merged
     * into the credential's encrypted data blob, keyed by the JSON field name the provider uses
     * ({@code instance_url}, {@code region}, {@code tenant_id}, {@code realmId}).
     *
     * <p>Empty map when the provider has no quirks or when none of the declared quirk fields are
     * present in the response - e.g. Salesforce always returns {@code instance_url}, but a
     * mis-configured quirk field name would silently produce an empty map rather than crash.
     */
    public Map<String, String> extractQuirkFields(JsonNode body, OAuth2ProviderConfig config) {
        OAuth2ProviderConfig.RefreshQuirks q = config.refresh().quirks();
        if (q == null || q.isEmpty() || body == null || !body.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        collectQuirkField(body, q.instanceUrlField(), out);
        collectQuirkField(body, q.regionField(), out);
        collectQuirkField(body, q.tenantIdField(), out);
        collectQuirkField(body, q.realmIdField(), out);
        return out;
    }

    private static void collectQuirkField(JsonNode body, String fieldName, Map<String, String> out) {
        if (fieldName == null) return;
        String v = textOrNull(body, fieldName);
        if (v != null) {
            out.put(fieldName, v);
        }
    }

    // ─────────────────────────── internals ───────────────────────────

    private HttpEntity<?> finalizeRequest(
            OAuth2ProviderConfig config,
            MultiValueMap<String, String> body,
            String clientId,
            String clientSecret
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        // A descriptive User-Agent on every token request. Some providers (Reddit's
        // www.reddit.com/api/v1/access_token is the canonical case) rate-limit or reject
        // requests carrying the HTTP client's default agent, so the code exchange / refresh
        // 429s without one. A well-formed agent is universally accepted, so this is applied
        // for all providers, not gated on any provider name.
        headers.set(HttpHeaders.USER_AGENT, TOKEN_REQUEST_USER_AGENT);

        String secretParam = config.tokenExchange().clientSecretParam();
        switch (config.tokenAuthMethod()) {
            case BASIC -> {
                if (clientId != null && clientSecret != null) {
                    String raw = clientId + ":" + clientSecret;
                    String encoded = Base64.getEncoder()
                            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                    headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
                }
                // client_id still goes in the body for PKCE public clients (RFC 7636 §4.2).
                if (clientId != null) {
                    body.add(config.clientIdParam(), clientId);
                }
            }
            case POST -> {
                if (clientId != null) {
                    body.add(config.clientIdParam(), clientId);
                }
                if (clientSecret != null) {
                    body.add(secretParam, clientSecret);
                }
            }
            case NONE -> {
                // Public PKCE client - send only client_id, no secret.
                if (clientId != null) {
                    body.add(config.clientIdParam(), clientId);
                }
            }
        }

        // Non-RFC providers (TikTok for Business) want the same params as a JSON object body
        // instead of form-urlencoded. Every value the flow assembled is single-valued, so a flat
        // {key: value} JSON object is a faithful re-encoding.
        if (config.tokenExchange().requestFormat() == RequestFormat.JSON) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            ObjectNode json = JsonNodeFactory.instance.objectNode();
            body.forEach((key, values) -> {
                if (values != null && !values.isEmpty() && values.get(0) != null) {
                    json.put(key, values.get(0));
                }
            });
            return new HttpEntity<>(json.toString(), headers);
        }

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        return new HttpEntity<>(body, headers);
    }

    private static void appendParam(StringBuilder url, String key, String value, boolean withAmpersand) {
        if (withAmpersand) url.append('&');
        url.append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String appendQueryParams(String url, MultiValueMap<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder out = new StringBuilder(url);
        boolean hasQuery = url.contains("?");
        boolean[] first = { !hasQuery || url.endsWith("?") || url.endsWith("&") };
        params.forEach((key, values) -> {
            if (values == null) {
                return;
            }
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                if (first[0]) {
                    if (!out.toString().endsWith("?") && !out.toString().endsWith("&")) {
                        out.append(hasQuery ? '&' : '?');
                    }
                    first[0] = false;
                } else {
                    out.append('&');
                }
                out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return out.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) return null;
        String text = child.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }
}
