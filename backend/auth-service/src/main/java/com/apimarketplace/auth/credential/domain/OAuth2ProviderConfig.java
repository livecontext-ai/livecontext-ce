package com.apimarketplace.auth.credential.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed, strongly-typed OAuth2 provider configuration extracted from a credential template's
 * {@code metadata.oauth2Config} block.
 *
 * <p>This record is the single source of truth consumed by {@link
 * com.apimarketplace.auth.credential.service.OAuth2Engine} - once a config is built from the raw
 * JSON, no downstream code should read {@code Map<String, Object>} metadata again.
 *
 * <p>All fields are nullable or defaulted, so adding a new field to the schema never breaks
 * existing provider JSONs. The engine applies RFC 6749 / RFC 7636 defaults when a field is
 * absent, so the vast majority of providers need only {@code authorizationUrl}, {@code tokenUrl},
 * and {@code scopes}.
 *
 * <p>{@code clientIdParam} names the request parameter that carries the OAuth client identifier on
 * both the authorize URL and the token endpoint body. It defaults to the RFC 6749 {@code client_id};
 * a provider that renames it (TikTok uses {@code client_key}) declares
 * {@code "clientIdParam": "client_key"} in its {@code oauth2Config} JSON, no per-provider code.
 */
public record OAuth2ProviderConfig(
        String authorizationUrl,
        String tokenUrl,
        String refreshUrl,
        List<String> scopes,
        String scopeDelimiter,
        AuthMethod tokenAuthMethod,
        TokenParamsLocation tokenParamsLocation,
        boolean pkceEnabled,
        Map<String, String> authorizeExtraParams,
        RefreshConfig refresh,
        String grantType,
        String clientIdParam,
        TokenExchangeConfig tokenExchange
) {

    /** How client credentials are transmitted on the token endpoint. */
    public enum AuthMethod {
        /** {@code client_id} and {@code client_secret} in the request body (RFC 6749 §2.3.1 alt). */
        POST,
        /** HTTP Basic auth header (RFC 6749 §2.3.1 default). */
        BASIC,
        /** Public client - no client secret (used with PKCE). */
        NONE
    }

    /** Where token endpoint parameters are transmitted. */
    public enum TokenParamsLocation {
        /** Standard OAuth form body parameters. */
        FORM,
        /** Provider-specific query parameters on the token URL. */
        QUERY
    }

    /**
     * Declarative refresh contract for this provider, mirroring the {@code oauth2Config.refresh}
     * block in the api-migration JSON schema.
     *
     * <p>When {@link #supported} is false, the provider does not expose a refresh flow at all
     * (client_credentials providers, long-lived PATs, …). In that case {@link #unsupportedReason}
     * holds a short operator-visible explanation and all other fields are {@code null}.
     *
     * <p>When {@link #supported} is true, callers may consult {@link #rotatesRefreshToken} to know
     * whether to persist a new refresh_token on every call, {@link #refreshTokenTtlDays} to
     * schedule proactive refresh before the refresh_token itself expires (Google = 180d,
     * Xero = 60d, QuickBooks = 100d, TikTok = 365d, …), and {@link #quirks} for non-RFC response
     * fields that must be harvested (Salesforce {@code instance_url}, Xero {@code tenant_id},
     * QuickBooks {@code realmId}, Zoho {@code region}).
     *
     * <p><strong>Note on {@code refreshTokenTtlDays}:</strong> this field is currently
     * <em>advisory</em> - the OAuth2 refresh scheduler's repository predicate selects candidates
     * by {@code access_token} expiry alone. For rotating providers (Xero, HubSpot, Zoho) that
     * suffices: each proactive access-token refresh also rotates the refresh_token, resetting
     * its TTL clock. For non-rotating providers with bounded RT-TTL (Google 180d, Microsoft 90d
     * idle), a credential that goes unused longer than {@code refreshTokenTtlDays} relies on the
     * lazy 401-retry path to re-authorize; a future phase will add a TTL-aware sweep driven by
     * {@code refresh_token_issued_at}. The field is carried here so migrations keep the data
     * available without another schema round-trip when that phase lands.
     */
    public record RefreshConfig(
            boolean supported,
            boolean rotatesRefreshToken,
            Integer refreshTokenTtlDays,
            String unsupportedReason,
            RefreshQuirks quirks
    ) {
        /** Plain RFC-6749 provider - refresh works, no rotation, no hard TTL, no quirks. */
        public static final RefreshConfig STANDARD =
                new RefreshConfig(true, false, null, null, RefreshQuirks.NONE);

        public RefreshConfig {
            quirks = quirks == null ? RefreshQuirks.NONE : quirks;
        }

        public static RefreshConfig unsupported(String reason) {
            return new RefreshConfig(false, false, null, reason, RefreshQuirks.NONE);
        }
    }

    /**
     * Non-RFC-6749 fields that a small handful of providers ship inside the token response and
     * that the engine must persist alongside the credential. Each field names the JSON key in the
     * provider's token response (e.g. {@code "instance_url"}, {@code "tenant_id"}). A {@code null}
     * value means the provider does not have that quirk.
     */
    public record RefreshQuirks(
            String instanceUrlField,
            String regionField,
            String tenantIdField,
            String realmIdField
    ) {
        public static final RefreshQuirks NONE = new RefreshQuirks(null, null, null, null);

        public boolean isEmpty() {
            return instanceUrlField == null && regionField == null
                    && tenantIdField == null && realmIdField == null;
        }
    }

    /**
     * Token-endpoint deviations from RFC 6749 that a small set of non-standard providers need.
     * Every field defaults to the RFC behaviour, so a provider that declares none behaves exactly
     * as before. Declared flat in the {@code oauth2Config} JSON and grouped here for the engine.
     *
     * <ul>
     *   <li>{@code clientSecretParam} - request-param name carrying the client secret in the token
     *       body (default {@code client_secret}; TikTok for Business uses {@code secret}).</li>
     *   <li>{@code codeParam} - request-param name carrying the authorization code in the token
     *       body (default {@code code}; TikTok for Business uses {@code auth_code}).</li>
     *   <li>{@code requestFormat} - how the token request body is encoded: RFC form-urlencoded
     *       ({@link RequestFormat#FORM}, default) or a JSON object ({@link RequestFormat#JSON};
     *       TikTok for Business).</li>
     *   <li>{@code responsePath} - JSON field the access/refresh tokens are nested under in the
     *       token response ({@code null} = RFC top level; TikTok for Business nests under
     *       {@code data}).</li>
     * </ul>
     *
     * <p><strong>Known limitation - token expiry under a wrapper.</strong> The engine reads the
     * standard {@code expires_in} field inside the {@code responsePath} object. A provider that
     * names its expiry differently (TikTok for Business uses {@code access_token_expires_in})
     * yields a null {@code expiresIn()}, so no {@code expires_at} is stored and the proactive
     * refresh scheduler skips the credential; it still refreshes lazily on the next 401. A
     * per-provider expiry-field mapping is future work.
     */
    public record TokenExchangeConfig(
            String clientSecretParam,
            String codeParam,
            RequestFormat requestFormat,
            String responsePath
    ) {
        /** Token request body encoding. */
        public enum RequestFormat { FORM, JSON }

        /** Plain RFC-6749 token endpoint: client_secret + code, form body, top-level response. */
        public static final TokenExchangeConfig STANDARD =
                new TokenExchangeConfig("client_secret", "code", RequestFormat.FORM, null);

        public TokenExchangeConfig {
            clientSecretParam = clientSecretParam == null || clientSecretParam.isBlank()
                    ? "client_secret" : clientSecretParam;
            codeParam = codeParam == null || codeParam.isBlank() ? "code" : codeParam;
            requestFormat = requestFormat == null ? RequestFormat.FORM : requestFormat;
            responsePath = responsePath == null || responsePath.isBlank() ? null : responsePath;
        }
    }

    public OAuth2ProviderConfig {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        scopeDelimiter = scopeDelimiter == null || scopeDelimiter.isEmpty() ? " " : scopeDelimiter;
        tokenAuthMethod = tokenAuthMethod == null ? AuthMethod.POST : tokenAuthMethod;
        tokenParamsLocation = tokenParamsLocation == null ? TokenParamsLocation.FORM : tokenParamsLocation;
        authorizeExtraParams = authorizeExtraParams == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(authorizeExtraParams));
        refresh = refresh == null ? RefreshConfig.STANDARD : refresh;
        grantType = grantType == null || grantType.isBlank() ? "authorizationCode" : grantType;
        clientIdParam = clientIdParam == null || clientIdParam.isBlank() ? "client_id" : clientIdParam;
        tokenExchange = tokenExchange == null ? TokenExchangeConfig.STANDARD : tokenExchange;
    }

    public OAuth2ProviderConfig(
            String authorizationUrl,
            String tokenUrl,
            String refreshUrl,
            List<String> scopes,
            String scopeDelimiter,
            AuthMethod tokenAuthMethod,
            boolean pkceEnabled,
            Map<String, String> authorizeExtraParams,
            RefreshConfig refresh
    ) {
        this(authorizationUrl, tokenUrl, refreshUrl, scopes, scopeDelimiter, tokenAuthMethod,
                TokenParamsLocation.FORM, pkceEnabled, authorizeExtraParams, refresh,
                "authorizationCode", "client_id", TokenExchangeConfig.STANDARD);
    }

    /**
     * Backward-compatible constructor matching the pre-{@code clientIdParam} canonical signature.
     * Defaults {@code clientIdParam} to the RFC 6749 {@code client_id} so existing call sites keep
     * compiling unchanged; providers that rename the param go through {@link #fromJson(JsonNode)}.
     */
    public OAuth2ProviderConfig(
            String authorizationUrl,
            String tokenUrl,
            String refreshUrl,
            List<String> scopes,
            String scopeDelimiter,
            AuthMethod tokenAuthMethod,
            TokenParamsLocation tokenParamsLocation,
            boolean pkceEnabled,
            Map<String, String> authorizeExtraParams,
            RefreshConfig refresh,
            String grantType
    ) {
        this(authorizationUrl, tokenUrl, refreshUrl, scopes, scopeDelimiter, tokenAuthMethod,
                tokenParamsLocation, pkceEnabled, authorizeExtraParams, refresh, grantType,
                "client_id", TokenExchangeConfig.STANDARD);
    }

    /** Joined scope string using the configured delimiter. Empty string if no scopes. */
    public String joinedScopes() {
        return scopes.isEmpty() ? "" : String.join(scopeDelimiter, scopes);
    }

    /**
     * Return a copy of this config with the scope list replaced, every other field preserved.
     *
     * <p>Used by the BYOK initiate path: a user bringing their own OAuth client is not bound by
     * the platform-template scope ceiling, so the service swaps in the user's full scope set
     * before building the authorize URL. Keeping this on the record (rather than re-constructing
     * inline at the call site) means a future field added to the config can't be silently dropped
     * when scopes are overridden.
     */
    public OAuth2ProviderConfig withScopes(List<String> newScopes) {
        return new OAuth2ProviderConfig(
                authorizationUrl, tokenUrl, refreshUrl, newScopes, scopeDelimiter,
                tokenAuthMethod, tokenParamsLocation, pkceEnabled, authorizeExtraParams, refresh,
                grantType, clientIdParam, tokenExchange);
    }

    /**
     * Return a copy with the authorization and token URLs replaced, every other field preserved.
     *
     * <p>Used to resolve per-instance host placeholders (Shopify {@code {shop}}, Zendesk
     * {@code {subdomain}}, NetSuite {@code {account_id}}, Workday {@code {tenant}}/{@code {hostname}},
     * Marketo {@code {munchkin_id}}) in the OAuth authorize/token URLs before the redirect. The
     * values come from the user at connect time (collected as credential fields the importer
     * derived from the URL templates), so this substitution is fully data-driven - no per-provider
     * code. A {@code null} argument keeps the current URL unchanged.
     */
    public OAuth2ProviderConfig withUrls(String newAuthorizationUrl, String newTokenUrl) {
        return new OAuth2ProviderConfig(
                newAuthorizationUrl != null ? newAuthorizationUrl : authorizationUrl,
                newTokenUrl != null ? newTokenUrl : tokenUrl,
                refreshUrl, scopes, scopeDelimiter,
                tokenAuthMethod, tokenParamsLocation, pkceEnabled, authorizeExtraParams, refresh,
                grantType, clientIdParam, tokenExchange);
    }

    /** Token endpoint falls back to {@code tokenUrl} when {@code refreshUrl} is not set. */
    public String effectiveRefreshUrl() {
        return refreshUrl != null && !refreshUrl.isBlank() ? refreshUrl : tokenUrl;
    }

    /**
     * Parse an {@code oauth2Config} JSON block into a typed config. Unknown fields are ignored;
     * missing fields get RFC-standard defaults. Returns {@code null} if the node is missing or
     * does not contain the required OAuth2 URLs. {@code client_credentials} providers do not
     * have an authorization URL, so only {@code tokenUrl} is required for that grant.
     */
    public static OAuth2ProviderConfig fromJson(JsonNode oauth2Config) {
        if (oauth2Config == null || oauth2Config.isMissingNode() || oauth2Config.isNull()) {
            return null;
        }

        String authUrl = textOrNull(oauth2Config, "authorizationUrl");
        String tokenUrl = textOrNull(oauth2Config, "tokenUrl");
        String grantType = textOrNull(oauth2Config, "grantType");
        boolean clientCredentials = "client_credentials".equalsIgnoreCase(grantType);
        if (tokenUrl == null || (!clientCredentials && authUrl == null)) {
            return null;
        }

        return new OAuth2ProviderConfig(
                authUrl,
                tokenUrl,
                textOrNull(oauth2Config, "refreshUrl"),
                parseScopes(oauth2Config.path("scopes")),
                textOrNull(oauth2Config, "scopeDelimiter"),
                parseAuthMethod(textOrNull(oauth2Config, "authMethod")),
                parseTokenParamsLocation(textOrNull(oauth2Config, "tokenParamsLocation")),
                oauth2Config.path("pkce").asBoolean(false),
                parseStringMap(oauth2Config.path("authorizeExtraParams")),
                parseRefresh(oauth2Config.path("refresh")),
                grantType,
                textOrNull(oauth2Config, "clientIdParam"),
                parseTokenExchange(oauth2Config)
        );
    }

    /**
     * Build the {@link TokenExchangeConfig} from the flat {@code oauth2Config} fields
     * ({@code clientSecretParam}, {@code codeParam}, {@code tokenRequestFormat},
     * {@code tokenResponsePath}). All optional; each absent field keeps the RFC default.
     */
    private static TokenExchangeConfig parseTokenExchange(JsonNode oauth2Config) {
        return new TokenExchangeConfig(
                textOrNull(oauth2Config, "clientSecretParam"),
                textOrNull(oauth2Config, "codeParam"),
                parseRequestFormat(textOrNull(oauth2Config, "tokenRequestFormat")),
                textOrNull(oauth2Config, "tokenResponsePath")
        );
    }

    private static TokenExchangeConfig.RequestFormat parseRequestFormat(String raw) {
        return "json".equalsIgnoreCase(raw)
                ? TokenExchangeConfig.RequestFormat.JSON
                : TokenExchangeConfig.RequestFormat.FORM;
    }

    public boolean isClientCredentials() {
        return "client_credentials".equalsIgnoreCase(grantType);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        String text = child.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private static List<String> parseScopes(JsonNode scopesNode) {
        if (scopesNode.isArray()) {
            List<String> result = new ArrayList<>(scopesNode.size());
            scopesNode.forEach(n -> {
                String s = n.asText(null);
                if (s != null && !s.isBlank()) {
                    result.add(s);
                }
            });
            return result;
        }
        if (scopesNode.isTextual()) {
            // Legacy: single space-delimited string.
            String text = scopesNode.asText().trim();
            if (text.isEmpty()) return List.of();
            return List.of(text.split("\\s+"));
        }
        return List.of();
    }

    private static AuthMethod parseAuthMethod(String raw) {
        if (raw == null) return AuthMethod.POST;
        return switch (raw.toLowerCase()) {
            case "client_secret_basic", "basic" -> AuthMethod.BASIC;
            case "none", "public" -> AuthMethod.NONE;
            default -> AuthMethod.POST; // client_secret_post or unknown → safe default
        };
    }

    private static TokenParamsLocation parseTokenParamsLocation(String raw) {
        if (raw == null) return TokenParamsLocation.FORM;
        return "query".equalsIgnoreCase(raw)
                ? TokenParamsLocation.QUERY
                : TokenParamsLocation.FORM;
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String v = e.getValue().asText(null);
            if (v != null) {
                result.put(e.getKey(), v);
            }
        });
        return result;
    }

    /**
     * Parse the {@code refresh} sub-block. If absent or malformed, defaults to
     * {@link RefreshConfig#STANDARD} - the import-time validator enforces presence, so hitting
     * the default at runtime means either a fallback path (catalog down, custom API without a
     * template) or a data drift worth alerting on.
     */
    private static RefreshConfig parseRefresh(JsonNode refreshNode) {
        if (refreshNode == null || refreshNode.isMissingNode() || refreshNode.isNull()
                || !refreshNode.isObject()) {
            return RefreshConfig.STANDARD;
        }
        boolean supported = refreshNode.path("supported").asBoolean(true);
        if (!supported) {
            return RefreshConfig.unsupported(textOrNull(refreshNode, "reason"));
        }
        boolean rotates = refreshNode.path("rotatesRefreshToken").asBoolean(false);
        Integer ttlDays = refreshNode.has("refreshTokenTtlDays") && refreshNode.path("refreshTokenTtlDays").isNumber()
                ? refreshNode.path("refreshTokenTtlDays").asInt()
                : null;
        return new RefreshConfig(true, rotates, ttlDays, null, parseQuirks(refreshNode.path("quirks")));
    }

    private static RefreshQuirks parseQuirks(JsonNode quirksNode) {
        if (quirksNode == null || !quirksNode.isObject()) {
            return RefreshQuirks.NONE;
        }
        return new RefreshQuirks(
                textOrNull(quirksNode, "instanceUrlField"),
                textOrNull(quirksNode, "regionField"),
                textOrNull(quirksNode, "tenantIdField"),
                textOrNull(quirksNode, "realmIdField")
        );
    }
}
