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
        TokenExchangeConfig tokenExchange,
        AccessTokenGrant longLivedExchange,
        UserScopeConfig userScopes
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
            RefreshQuirks quirks,
            AccessTokenGrant accessTokenGrant
    ) {
        /** Plain RFC-6749 provider - refresh works, no rotation, no hard TTL, no quirks. */
        public static final RefreshConfig STANDARD =
                new RefreshConfig(true, false, null, null, RefreshQuirks.NONE, null);

        public RefreshConfig {
            quirks = quirks == null ? RefreshQuirks.NONE : quirks;
        }

        /** Pre-{@code accessTokenGrant} signature kept so existing call sites compile unchanged. */
        public RefreshConfig(boolean supported, boolean rotatesRefreshToken, Integer refreshTokenTtlDays,
                             String unsupportedReason, RefreshQuirks quirks) {
            this(supported, rotatesRefreshToken, refreshTokenTtlDays, unsupportedReason, quirks, null);
        }

        public static RefreshConfig unsupported(String reason) {
            return new RefreshConfig(false, false, null, reason, RefreshQuirks.NONE, null);
        }
    }

    /**
     * A non-RFC "token grant" call: GET on a provider URL whose query carries a
     * {@code grant_type} plus the CURRENT access token (there is no refresh_token involved).
     * Meta is the canonical family:
     *
     * <ul>
     *   <li>Instagram Login short→long exchange: {@code GET graph.instagram.com/access_token
     *       ?grant_type=ig_exchange_token&client_secret=…&access_token=<short-lived>}</li>
     *   <li>Instagram long-lived renewal: {@code GET graph.instagram.com/refresh_access_token
     *       ?grant_type=ig_refresh_token&access_token=<long-lived>}</li>
     *   <li>Facebook short→long exchange AND renewal: {@code GET graph.facebook.com/vXX.0/oauth/access_token
     *       ?grant_type=fb_exchange_token&client_id=…&client_secret=…&fb_exchange_token=<token>}</li>
     * </ul>
     *
     * Used in two places, same shape: {@code oauth2Config.longLivedExchange} (run once right
     * after the code exchange in the connect callback) and {@code oauth2Config.refresh.accessTokenGrant}
     * (run by the refresh scheduler / lazy-401 path instead of the RFC refresh_token grant).
     * The response is a standard token JSON ({@code access_token} + {@code expires_in}).
     *
     * @param url              absolute endpoint URL
     * @param grantType        value of the {@code grant_type} query param
     * @param tokenParam       query param name carrying the current access token
     *                         (IG: {@code access_token}; FB: {@code fb_exchange_token})
     * @param sendClientId     include {@code client_id} in the query (FB: yes, IG: no)
     * @param sendClientSecret include {@code client_secret} in the query
     *                         (IG renewal: no; both exchanges: yes)
     */
    public record AccessTokenGrant(
            String url,
            String grantType,
            String tokenParam,
            boolean sendClientId,
            boolean sendClientSecret
    ) {
        public AccessTokenGrant {
            tokenParam = tokenParam == null || tokenParam.isBlank() ? "access_token" : tokenParam;
        }

        /** Valid only when both the endpoint and the grant_type are declared. */
        public boolean isValid() {
            return url != null && !url.isBlank() && grantType != null && !grantType.isBlank();
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

    /**
     * A second scope family that the provider carries in its OWN authorize parameter instead of
     * the standard {@code scope} one.
     *
     * <p>Slack is the reference case and the reason this exists: its {@code scope} parameter
     * accepts BOT scopes only, and a user-token scope such as {@code search:read} has to travel
     * as {@code user_scope=search:read}. Putting one in {@code scope} makes Slack reject the
     * whole installation with {@code invalid_scope} ("Invalid permissions requested"), so the
     * split decides whether the integration can be connected at all, not how it looks.
     *
     * <p>Routing is by MEMBERSHIP: {@code scopes} lists the strings that belong to the family, and
     * every scope actually being requested is sent through whichever parameter claims it. That is
     * what lets a family member sit in {@code byokOnlyScopes} (absent from the platform-shared
     * request, present in the BYOK/CE one) without being declared twice.
     *
     * <p>A provider that declares no second family gets {@link #NONE}: {@code scope} carries
     * everything and the authorize URL is byte-identical to what it was before this field existed.
     *
     * @param param  the authorize-request parameter carrying the family ({@code user_scope})
     * @param scopes the scope strings belonging to it
     */
    public record UserScopeConfig(String param, List<String> scopes) {

        /** No second scope family: every scope goes in the standard {@code scope} parameter. */
        public static final UserScopeConfig NONE = new UserScopeConfig(null, List.of());

        public UserScopeConfig {
            param = param == null || param.isBlank() ? null : param;
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        /** True when no member scopes are declared: the provider has a single scope family. */
        public boolean isEmpty() {
            return scopes.isEmpty();
        }

        /**
         * Whether the members can actually be sent: there has to be a parameter to carry them.
         * Members without a parameter are DROPPED from the request rather than falling back to
         * {@code scope}, which is the whole point - see {@link #contains(String)}.
         */
        public boolean canRoute() {
            return param != null && !scopes.isEmpty();
        }

        /** Whether {@code scope} belongs to this family (and so must never go in {@code scope=}). */
        public boolean contains(String scope) {
            return scopes.contains(scope);
        }
    }

    public OAuth2ProviderConfig {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        userScopes = userScopes == null ? UserScopeConfig.NONE : userScopes;
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
                "authorizationCode", "client_id", TokenExchangeConfig.STANDARD, null,
                UserScopeConfig.NONE);
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
                "client_id", TokenExchangeConfig.STANDARD, null, UserScopeConfig.NONE);
    }

    /**
     * Joined string of EVERY requested scope, both families, using the configured delimiter.
     * Empty string if no scopes.
     *
     * <p>This is the record of what the user was asked to grant (stored on the OAuth state and
     * used as the fallback granted-scope list when the provider echoes none back), not what goes
     * in the {@code scope} request parameter - see {@link #joinedPrimaryScopes()} for that.
     */
    public String joinedScopes() {
        return join(scopes);
    }

    /**
     * The scopes that travel in the standard {@code scope} parameter: everything not claimed by
     * the {@link UserScopeConfig} family. Identical to {@link #scopes()} for the providers that
     * declare no second family, which is all of them but Slack.
     */
    public List<String> primaryScopes() {
        if (userScopes.isEmpty()) {
            return scopes;
        }
        return scopes.stream().filter(s -> !userScopes.contains(s)).toList();
    }

    /**
     * Whether a declared family member would be silently dropped from this request because no
     * parameter carries it. Nothing in the seed corpus reaches this state (the validator refuses
     * it), and dropping is the deliberate choice over the alternative: emitting the member on
     * {@code scope} is the two-month Slack outage, and it fails for EVERY user rather than for the
     * one capability. Exposed so the service can log it instead of losing it in silence.
     */
    public List<String> unroutableUserScopes() {
        if (userScopes.isEmpty() || userScopes.canRoute()) {
            return List.of();
        }
        return scopes.stream().filter(userScopes::contains).toList();
    }

    /**
     * The scopes that travel in the provider's own user-scope parameter. Empty when no family is
     * declared, and empty when none of its members are part of THIS request - which is what keeps
     * the platform-shared authorize URL free of a family that only BYOK asks for.
     */
    public List<String> requestedUserScopes() {
        if (!userScopes.canRoute()) {
            return List.of();
        }
        return scopes.stream().filter(userScopes::contains).toList();
    }

    /** {@link #primaryScopes()} joined with the configured delimiter. */
    public String joinedPrimaryScopes() {
        return join(primaryScopes());
    }

    /** {@link #requestedUserScopes()} joined with the configured delimiter. */
    public String joinedUserScopes() {
        return join(requestedUserScopes());
    }

    private String join(List<String> values) {
        return values.isEmpty() ? "" : String.join(scopeDelimiter, values);
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
                grantType, clientIdParam, tokenExchange, longLivedExchange, userScopes);
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
                grantType, clientIdParam, tokenExchange, longLivedExchange, userScopes);
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
                parseTokenExchange(oauth2Config),
                parseAccessTokenGrant(oauth2Config.path("longLivedExchange")),
                parseUserScopes(oauth2Config)
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

    /**
     * Parse the optional second scope family from the flat {@code userScopeParam} /
     * {@code userScopes} fields.
     *
     * <p>Absent, empty, or a shape no members can be read from yields {@link UserScopeConfig#NONE}
     * (the pre-existing single-parameter behaviour). Whenever members ARE identified, they are
     * carried even if nothing can route them, so they are dropped from the request rather than
     * falling back into {@code scope} - which is the shape that made Slack refuse every install.
     */
    private static UserScopeConfig parseUserScopes(JsonNode oauth2Config) {
        JsonNode declared = oauth2Config.path("userScopes");
        List<String> members = parseScopes(declared);
        if (members.isEmpty()) {
            // Nothing readable: an absent field, an empty array, or a shape no member can be read
            // from (an object, a number). This is the one case that cannot fail closed - scopes we
            // never identified cannot be held out of `scope` - so the seed validator refusing the
            // shape is the only guard, and a malformed catalog bundle would get the old behaviour.
            return UserScopeConfig.NONE;
        }
        // Routing requires BOTH a valid array declaration and a parameter. A null param is carried
        // through DELIBERATELY rather than collapsing to NONE: the members are then excluded from
        // `scope` and sent nowhere (fail closed). Collapsing would put them back in `scope`, which
        // is the outage, on the path that actually builds configs in production - including rows
        // written by the signed catalog bundle, where no seed validator ever runs.
        //
        // A non-array `userScopes` (the legacy space-delimited string form that `scopes` tolerates)
        // is NOT a valid family: the seed validator refuses it, so accepting it here would make the
        // two layers disagree on what a valid seed is. Its members are still identified, so they
        // are dropped rather than sent in the bot parameter.
        String param = declared.isArray() ? textOrNull(oauth2Config, "userScopeParam") : null;
        return new UserScopeConfig(param, members);
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
        return new RefreshConfig(true, rotates, ttlDays, null, parseQuirks(refreshNode.path("quirks")),
                parseAccessTokenGrant(refreshNode.path("accessTokenGrant")));
    }

    /**
     * Parse an {@code accessTokenGrant} / {@code longLivedExchange} block. Returns {@code null}
     * when the block is absent OR incomplete (missing url/grantType) - an invalid block must
     * degrade to "feature off", never to a broken half-configured grant call.
     */
    private static AccessTokenGrant parseAccessTokenGrant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        AccessTokenGrant grant = new AccessTokenGrant(
                textOrNull(node, "url"),
                textOrNull(node, "grantType"),
                textOrNull(node, "tokenParam"),
                node.path("sendClientId").asBoolean(false),
                node.path("sendClientSecret").asBoolean(false)
        );
        return grant.isValid() ? grant : null;
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
