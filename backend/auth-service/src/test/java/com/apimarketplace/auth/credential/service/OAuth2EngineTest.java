package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2TokenResponse;
import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig;
import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig.AuthMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuth2Engine")
class OAuth2EngineTest {

    private final OAuth2Engine engine = new OAuth2Engine();
    private final ObjectMapper json = new ObjectMapper();

    private static final String CALLBACK = "https://livecontext.ai/api/credentials/oauth2/callback";

    // ─────────────────── buildAuthorizationUrl ───────────────────

    @Nested
    @DisplayName("buildAuthorizationUrl")
    class BuildAuthorizationUrl {

        @Test
        @DisplayName("standard provider: RFC 6749 params, space-delimited scopes")
        void standardProvider() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of("read", "write"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            String url = engine.buildAuthorizationUrl(cfg, "client-abc", "state-xyz", CALLBACK, null);
            Map<String, String> params = parseQuery(url);

            assertThat(params)
                    .containsEntry("response_type", "code")
                    .containsEntry("client_id", "client-abc")
                    .containsEntry("redirect_uri", CALLBACK)
                    .containsEntry("state", "state-xyz")
                    .containsEntry("scope", "read write");
            assertThat(params).doesNotContainKey("code_challenge");
        }

        @Test
        @DisplayName("renamed client id param (TikTok client_key): carried on the authorize URL, no literal client_id")
        void renamedClientIdParam() throws Exception {
            // TikTok is the RFC oddball: its authorize endpoint expects client_key, not client_id.
            // The provider declares clientIdParam in its oauth2Config JSON; the engine must honour it
            // (the live symptom was TikTok's "client_key" error when the engine emitted client_id).
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://www.tiktok.com/v2/auth/authorize/",
                      "tokenUrl": "https://open.tiktokapis.com/v2/oauth/token/",
                      "clientIdParam": "client_key",
                      "scopes": ["user.info.basic"]
                    }
                    """));

            Map<String, String> params =
                    parseQuery(engine.buildAuthorizationUrl(cfg, "client-abc", "state-xyz", CALLBACK, null));

            assertThat(params).containsEntry("client_key", "client-abc");
            assertThat(params).doesNotContainKey("client_id");
        }

        @Test
        @DisplayName("Reddit-shaped config: authorize URL carries duration=permanent + scopes; fromJson yields BASIC auth")
        void redditShapedAuthorizeUrl() throws Exception {
            // Mirrors scripts/api-migrations/reddit.json's oauth2Config. duration=permanent is the
            // single param that makes Reddit issue a refresh_token, so assert it reaches the authorize
            // URL, together with the space-delimited scopes and client_secret_basic surviving fromJson.
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://www.reddit.com/api/v1/authorize",
                      "tokenUrl": "https://www.reddit.com/api/v1/access_token",
                      "scopes": ["identity","read","submit","edit","vote","save","subscribe","privatemessages","report","history","mysubreddits","flair"],
                      "scopeDelimiter": " ",
                      "authMethod": "client_secret_basic",
                      "pkce": false,
                      "authorizeExtraParams": { "duration": "permanent" }
                    }
                    """));

            Map<String, String> params =
                    parseQuery(engine.buildAuthorizationUrl(cfg, "cid", "state-1", CALLBACK, null));

            assertThat(params).containsEntry("duration", "permanent");
            assertThat(params).containsEntry("response_type", "code");
            assertThat(params.get("scope")).contains("submit").contains("identity");
            assertThat(cfg.tokenAuthMethod()).isEqualTo(AuthMethod.BASIC);
        }

        @Test
        @DisplayName("comma-delimited scopes (e.g. Slack-style)")
        void commaDelimitedScopes() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of("chat:write", "channels:read"),
                    ",",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            String url = engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null);
            assertThat(parseQuery(url)).containsEntry("scope", "chat:write,channels:read");
        }

        @Test
        @DisplayName("pkce: adds code_challenge + code_challenge_method=S256")
        void pkceAddsChallenge() {
            OAuth2ProviderConfig cfg = standardConfig(true);
            PkceService.PkceChallenge pkce = new PkceService.PkceChallenge(
                    "verifier-43-chars-long-dummy-value-for-test-ok",
                    "CHALLENGE_DUMMY",
                    "S256"
            );

            String url = engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, pkce);
            Map<String, String> params = parseQuery(url);

            assertThat(params)
                    .containsEntry("code_challenge", "CHALLENGE_DUMMY")
                    .containsEntry("code_challenge_method", "S256");
        }

        @Test
        @DisplayName("authorizeExtraParams: provider-specific params injected verbatim")
        void extraParamsInjected() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of("read"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of("audience", "api.atlassian.com", "prompt", "login"),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            String url = engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null);
            Map<String, String> params = parseQuery(url);

            assertThat(params)
                    .containsEntry("audience", "api.atlassian.com")
                    .containsEntry("prompt", "login");
        }

        @Test
        @DisplayName("google contract (post-shim): access_type/prompt declared in JSON are forwarded verbatim")
        void googleExtrasFromJson() {
            // Post-Phase-1: the legacy hardcoded Google shim is gone. google_*.json files now
            // declare access_type=offline + prompt=consent explicitly via authorizeExtraParams,
            // and the generic extras-forwarding loop handles them like any other provider.
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    null,
                    List.of("openid"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of("access_type", "offline", "prompt", "consent"),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null));

            assertThat(params)
                    .containsEntry("access_type", "offline")
                    .containsEntry("prompt", "consent");
        }

        @Test
        @DisplayName("non-google provider with empty extras: no implicit access_type/prompt injection")
        void noImplicitInjectionAfterShimRemoval() {
            // Regression: when the shim was in place, any URL hostname-matching google.com got
            // access_type/prompt injected silently. The shim is gone; this test locks that in.
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    null,
                    List.of("openid"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null));

            assertThat(params).doesNotContainKey("access_type");
            assertThat(params).doesNotContainKey("prompt");
        }

        @Test
        @DisplayName("handles authorization URL that already has a query string")
        void authorizeUrlWithExistingQuery() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize?foo=bar",
                    "https://example.com/token",
                    null,
                    List.of("read"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            String url = engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null);

            assertThat(url).startsWith("https://example.com/authorize?foo=bar&response_type=code");
        }

        @Test
        @DisplayName("no scope: scope param is omitted")
        void noScopeOmitted() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            String url = engine.buildAuthorizationUrl(cfg, "c", "s", CALLBACK, null);
            assertThat(parseQuery(url)).doesNotContainKey("scope");
        }

        @Test
        @DisplayName("null authorizationUrl (client_credentials config) → NPE, never a malformed URL")
        void nullAuthorizationUrlThrows() {
            // client_credentials providers carry a null authorizationUrl (OAuth2ProviderConfig.fromJson
            // only requires tokenUrl for that grant). buildAuthorizationUrl is authorization_code-only
            // and must fail loudly rather than emit a "null?response_type=code..." redirect URL.
            OAuth2ProviderConfig nullUrl = new OAuth2ProviderConfig(
                    null,
                    "https://example.com/token",
                    null,
                    List.of("read"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            assertThatThrownBy(() -> engine.buildAuthorizationUrl(nullUrl, "c", "s", CALLBACK, null))
                    .isInstanceOf(NullPointerException.class);

            // Positive control: the SAME config but with a real authorizationUrl builds a valid redirect.
            // This pins the failure to the null authorizationUrl specifically (the only field that differs),
            // not to some other incidental null, and proves no "null?response_type=code..." URL is emitted.
            OAuth2ProviderConfig withUrl = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of("read"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );
            String url = engine.buildAuthorizationUrl(withUrl, "c", "s", CALLBACK, null);
            assertThat(url).startsWith("https://example.com/authorize");
        }

        @Test
        @DisplayName("pkce gating: even with PKCE enabled in config, a null challenge argument omits code_challenge (engine gates on the argument, not config.pkceEnabled)")
        void pkceGatingContract() {
            OAuth2ProviderConfig pkceCfg = standardConfig(true);

            // The supplied-challenge case is already pinned by pkceAddsChallenge. The behavior UNIQUE to
            // this test: the engine gates the PKCE params on the challenge ARGUMENT, not on
            // config.pkceEnabled(). With PKCE enabled in config but a null challenge supplied, the URL must
            // NOT carry code_challenge - OAuth2Service, not the engine, owns the decision to generate one.
            Map<String, String> withoutChallenge =
                    parseQuery(engine.buildAuthorizationUrl(pkceCfg, "c", "s", CALLBACK, null));
            assertThat(withoutChallenge)
                    .doesNotContainKey("code_challenge")
                    .doesNotContainKey("code_challenge_method");
        }

        // ─── {ui_locale} placeholder: consent-screen language ───

        /** Google-style config that opts in to the dynamic UI-locale param under {@code hl}. */
        private OAuth2ProviderConfig googleWithLocaleParam() {
            return new OAuth2ProviderConfig(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    null,
                    List.of("openid"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of("access_type", "offline", "hl", OAuth2Engine.UI_LOCALE_PLACEHOLDER),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );
        }

        @Test
        @DisplayName("ui_locale: locale provided → placeholder rendered with the app locale (hl=fr)")
        void uiLocaleSubstituted() {
            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(
                    googleWithLocaleParam(), "c", "s", CALLBACK, null, "fr"));

            assertThat(params)
                    .containsEntry("hl", "fr")
                    .containsEntry("access_type", "offline");
        }

        @Test
        @DisplayName("ui_locale: locale null → placeholder param dropped, other extras kept")
        void uiLocaleNullDropsParam() {
            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(
                    googleWithLocaleParam(), "c", "s", CALLBACK, null, null));

            assertThat(params).doesNotContainKey("hl");
            assertThat(params).containsEntry("access_type", "offline");
        }

        @Test
        @DisplayName("ui_locale: locale blank → placeholder param dropped (treated like null)")
        void uiLocaleBlankDropsParam() {
            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(
                    googleWithLocaleParam(), "c", "s", CALLBACK, null, "   "));

            assertThat(params).doesNotContainKey("hl");
        }

        @Test
        @DisplayName("ui_locale: locale-unaware 5-arg overload drops the placeholder (never leaks {ui_locale})")
        void uiLocaleFiveArgOverloadDropsPlaceholder() {
            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(
                    googleWithLocaleParam(), "c", "s", CALLBACK, null));

            assertThat(params).doesNotContainKey("hl");
            // The raw placeholder must NEVER be forwarded verbatim into the URL.
            assertThat(params).doesNotContainValue(OAuth2Engine.UI_LOCALE_PLACEHOLDER);
        }

        @Test
        @DisplayName("ui_locale: provider that did not opt in gets no locale param even when a locale is passed")
        void uiLocaleNoOptInNoInjection() {
            OAuth2ProviderConfig noOptIn = new OAuth2ProviderConfig(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    null,
                    List.of("openid"),
                    " ",
                    AuthMethod.POST,
                    false,
                    Map.of("access_type", "offline"),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            Map<String, String> params = parseQuery(engine.buildAuthorizationUrl(
                    noOptIn, "c", "s", CALLBACK, null, "fr"));

            assertThat(params).doesNotContainKey("hl");
            assertThat(params).doesNotContainKey("ui_locales");
            assertThat(params).containsEntry("access_type", "offline");
        }
    }

    // ─────────────────── buildTokenExchangeRequest ───────────────────

    @Nested
    @DisplayName("buildTokenExchangeRequest")
    class BuildTokenExchange {

        @Test
        @DisplayName("POST auth: client_id and client_secret in the body")
        void postAuth() {
            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    standardConfig(false), "code-abc", "client-id", "client-secret", CALLBACK, null);

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("grant_type")).isEqualTo("authorization_code");
            assertThat(body.getFirst("code")).isEqualTo("code-abc");
            assertThat(body.getFirst("redirect_uri")).isEqualTo(CALLBACK);
            assertThat(body.getFirst("client_id")).isEqualTo("client-id");
            assertThat(body.getFirst("client_secret")).isEqualTo("client-secret");

            HttpHeaders headers = req.getHeaders();
            assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
            assertThat(headers.getAccept()).contains(MediaType.APPLICATION_JSON);
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        @DisplayName("carries a descriptive User-Agent - Reddit's access_token endpoint 429s the HTTP client default agent")
        void tokenExchangeSendsUserAgent() {
            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    standardConfig(false), "code-abc", "client-id", "client-secret", CALLBACK, null);

            assertThat(req.getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo(OAuth2Engine.TOKEN_REQUEST_USER_AGENT);
        }

        @Test
        @DisplayName("renamed client id param (TikTok client_key): token body uses client_key, client_secret stays standard")
        void postAuthRenamedClientIdParam() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://www.tiktok.com/v2/auth/authorize/",
                      "tokenUrl": "https://open.tiktokapis.com/v2/oauth/token/",
                      "clientIdParam": "client_key",
                      "authMethod": "client_secret_post",
                      "scopes": ["user.info.basic"]
                    }
                    """));

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "code-abc", "client-id", "client-secret", CALLBACK, null);

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("client_key")).isEqualTo("client-id");
            assertThat(body.getFirst("client_id")).isNull();
            assertThat(body.getFirst("client_secret")).isEqualTo("client-secret");
        }

        @Test
        @DisplayName("renamed client id param on BASIC auth: client_key still emitted in the body for public-client parity")
        void basicAuthRenamedClientIdParam() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://provider/authorize",
                      "tokenUrl": "https://provider/token",
                      "clientIdParam": "client_key",
                      "authMethod": "client_secret_basic",
                      "scopes": ["read"]
                    }
                    """));

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "code-abc", "client-id", "client-secret", CALLBACK, null);

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("client_key")).isEqualTo("client-id");
            assertThat(body.getFirst("client_id")).isNull();
        }

        @Test
        @DisplayName("renamed client id param on the refresh_token request body")
        void refreshRenamedClientIdParam() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://www.tiktok.com/v2/auth/authorize/",
                      "tokenUrl": "https://open.tiktokapis.com/v2/oauth/token/",
                      "clientIdParam": "client_key",
                      "authMethod": "client_secret_post",
                      "scopes": ["user.info.basic"]
                    }
                    """));

            HttpEntity<?> req = engine.buildRefreshRequest(
                    cfg, "refresh-tok", "client-id", "client-secret");

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("grant_type")).isEqualTo("refresh_token");
            assertThat(body.getFirst("client_key")).isEqualTo("client-id");
            assertThat(body.getFirst("client_id")).isNull();
        }

        @Test
        @DisplayName("JSON token format (TikTok Business): JSON object body with app_id/secret/auth_code, application/json")
        void jsonTokenBody() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://business-api.tiktok.com/portal/auth",
                      "tokenUrl": "https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/",
                      "clientIdParam": "app_id",
                      "clientSecretParam": "secret",
                      "codeParam": "auth_code",
                      "tokenRequestFormat": "json",
                      "authMethod": "client_secret_post",
                      "scopes": ["ad.read"]
                    }
                    """));

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "the-auth-code", "app-123", "secret-xyz", CALLBACK, null);

            assertThat(req.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            // Body is a serialized JSON object, not a form MultiValueMap.
            assertThat(req.getBody()).isInstanceOf(String.class);
            JsonNode parsed = json.readTree((String) req.getBody());
            assertThat(parsed.path("app_id").asText()).isEqualTo("app-123");
            assertThat(parsed.path("secret").asText()).isEqualTo("secret-xyz");
            assertThat(parsed.path("auth_code").asText()).isEqualTo("the-auth-code");
            assertThat(parsed.path("grant_type").asText()).isEqualTo("authorization_code");
            // The RFC names must NOT leak into the JSON body.
            assertThat(parsed.has("client_id")).isFalse();
            assertThat(parsed.has("client_secret")).isFalse();
            assertThat(parsed.has("code")).isFalse();
        }

        @Test
        @DisplayName("default FORM format keeps application/x-www-form-urlencoded (regression symmetry with JSON)")
        void formTokenBodyContentType() {
            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    standardConfig(false), "code-abc", "cid", "csec", CALLBACK, null);

            assertThat(req.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
            assertThat(req.getBody()).isInstanceOf(MultiValueMap.class);
        }

        @Test
        @DisplayName("renamed client secret param on a FORM-body provider (rename is not JSON-only)")
        void formSecretParamRename() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://provider/authorize",
                      "tokenUrl": "https://provider/token",
                      "clientSecretParam": "secret",
                      "authMethod": "client_secret_post",
                      "scopes": ["read"]
                    }
                    """));

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "code-abc", "cid", "the-secret", CALLBACK, null);

            assertThat(form(req).getFirst("secret")).isEqualTo("the-secret");
            assertThat(form(req).getFirst("client_secret")).isNull();
        }

        @Test
        @DisplayName("BASIC auth: credentials in Authorization header, no client_secret in body")
        void basicAuth() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.BASIC,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "code-abc", "client-id", "client-secret", CALLBACK, null);

            String expectedHeader = "Basic " + Base64.getEncoder()
                    .encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expectedHeader);

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("client_id")).isEqualTo("client-id");
            assertThat(body.getFirst("client_secret")).isNull();
        }

        @Test
        @DisplayName("NONE auth (public PKCE client): only client_id in body, no secret")
        void noneAuth() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.NONE,
                    true,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    cfg, "code-abc", "client-id", null, CALLBACK, "verifier-xyz");

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) req.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst("client_id")).isEqualTo("client-id");
            assertThat(body.getFirst("client_secret")).isNull();
            assertThat(body.getFirst("code_verifier")).isEqualTo("verifier-xyz");
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        @DisplayName("PKCE: code_verifier added to token exchange body")
        void pkceVerifierInBody() {
            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    standardConfig(true), "code-abc", "cid", "csec", CALLBACK, "my-verifier");

            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("code_verifier")).isEqualTo("my-verifier");
        }

        @Test
        @DisplayName("no PKCE: code_verifier is not added")
        void noPkceNoVerifier() {
            HttpEntity<?> req = engine.buildTokenExchangeRequest(
                    standardConfig(false), "code-abc", "cid", "csec", CALLBACK, null);

            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("code_verifier")).isNull();
        }
    }

    // ─────────────────── buildRefreshRequest ───────────────────

    @Nested
    @DisplayName("buildRefreshRequest")
    class BuildRefresh {

        @Test
        @DisplayName("POST auth: sends grant_type=refresh_token with credentials in body")
        void refreshBodyShape() {
            HttpEntity<?> req = engine.buildRefreshRequest(
                    standardConfig(false), "my-refresh-token", "cid", "csec");

            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("grant_type")).isEqualTo("refresh_token");
            assertThat(form(req).getFirst("refresh_token")).isEqualTo("my-refresh-token");
            assertThat(form(req).getFirst("client_id")).isEqualTo("cid");
            assertThat(form(req).getFirst("client_secret")).isEqualTo("csec");
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        @DisplayName("carries a descriptive User-Agent on the refresh request (same reason as the exchange path)")
        void refreshSendsUserAgent() {
            HttpEntity<?> req = engine.buildRefreshRequest(
                    standardConfig(false), "my-refresh-token", "cid", "csec");

            assertThat(req.getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo(OAuth2Engine.TOKEN_REQUEST_USER_AGENT);
        }

        @Test
        @DisplayName("BASIC auth: refresh still sets Authorization header - load-bearing for Twitter/Spotify")
        void refreshWithBasicAuth() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.BASIC,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            HttpEntity<?> req = engine.buildRefreshRequest(
                    cfg, "rt", "cid", "csec");

            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("cid:csec".getBytes(StandardCharsets.UTF_8));
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("grant_type")).isEqualTo("refresh_token");
            assertThat(form(req).getFirst("refresh_token")).isEqualTo("rt");
            // client_secret must NOT be duplicated in the body when BASIC is used.
            assertThat(form(req).getFirst("client_secret")).isNull();
        }
    }

    // ─────────────────── parseTokenResponse ───────────────────

    @Nested
    @DisplayName("buildClientCredentialsRequest")
    class BuildClientCredentials {

        @Test
        @DisplayName("POST auth: sends grant_type=client_credentials with credentials in body")
        void clientCredentialsBodyShape() {
            HttpEntity<?> req = engine.buildClientCredentialsRequest(
                    standardConfig(false), "cid", "csec");

            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("grant_type")).isEqualTo("client_credentials");
            assertThat(form(req).getFirst("client_id")).isEqualTo("cid");
            assertThat(form(req).getFirst("client_secret")).isEqualTo("csec");
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        @DisplayName("carries a descriptive User-Agent on the client_credentials request (third path through finalizeRequest)")
        void clientCredentialsSendsUserAgent() {
            HttpEntity<?> req = engine.buildClientCredentialsRequest(standardConfig(false), "cid", "csec");

            assertThat(req.getHeaders().getFirst(HttpHeaders.USER_AGENT))
                    .isEqualTo(OAuth2Engine.TOKEN_REQUEST_USER_AGENT);
        }

        @Test
        @DisplayName("BASIC auth: client_credentials uses Authorization header and no body secret")
        void clientCredentialsWithBasicAuth() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    "https://example.com/authorize",
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.BASIC,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.STANDARD
            );

            HttpEntity<?> req = engine.buildClientCredentialsRequest(
                    cfg, "cid", "csec");

            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("cid:csec".getBytes(StandardCharsets.UTF_8));
            assertThat(req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
            assertThat(req.getBody()).isNotNull();
            assertThat(form(req).getFirst("grant_type")).isEqualTo("client_credentials");
            assertThat(form(req).getFirst("client_id")).isEqualTo("cid");
            assertThat(form(req).getFirst("client_secret")).isNull();
        }

        @Test
        @DisplayName("query token params: moves client_credentials body fields into token URL")
        void clientCredentialsQueryTokenParams() {
            OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                    null,
                    "https://example.com/token",
                    null,
                    List.of(),
                    " ",
                    AuthMethod.POST,
                    OAuth2ProviderConfig.TokenParamsLocation.QUERY,
                    false,
                    Map.of(),
                    OAuth2ProviderConfig.RefreshConfig.unsupported("client_credentials flow"),
                    "client_credentials"
            );

            HttpEntity<?> req =
                    engine.buildClientCredentialsRequest(cfg, "cid", "csec");
            OAuth2Engine.TokenRequest tokenRequest =
                    engine.materializeTokenRequest(cfg, "https://example.com/token", req);

            assertThat(parseQuery(tokenRequest.url()))
                    .containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", "cid")
                    .containsEntry("client_secret", "csec");
            assertThat(form(tokenRequest.entity())).isNotNull();
            assertThat(form(tokenRequest.entity())).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseTokenResponse")
    class ParseToken {

        @Test
        @DisplayName("standard RFC 6749 token response")
        void standardResponse() throws Exception {
            JsonNode body = json.readTree("""
                    {
                      "access_token": "AT",
                      "refresh_token": "RT",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "scope": "read write"
                    }
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body);

            assertThat(resp.accessToken()).isEqualTo("AT");
            assertThat(resp.refreshToken()).isEqualTo("RT");
            assertThat(resp.tokenType()).isEqualTo("Bearer");
            assertThat(resp.expiresIn()).isEqualTo(3600L);
            assertThat(resp.scope()).isEqualTo("read write");
        }

        @Test
        @DisplayName("tokenResponsePath: reads tokens nested under a wrapper (TikTok Business data)")
        void nestedResponsePath() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://business-api.tiktok.com/portal/auth",
                      "tokenUrl": "https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/",
                      "tokenResponsePath": "data",
                      "scopes": ["ad.read"]
                    }
                    """));
            JsonNode body = json.readTree("""
                    {"code":0,"message":"OK","data":{"access_token":"AT","refresh_token":"RT"}}
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body, cfg);

            assertThat(resp.accessToken()).isEqualTo("AT");
            assertThat(resp.refreshToken()).isEqualTo("RT");
        }

        @Test
        @DisplayName("known limitation: non-standard expiry field under the wrapper yields null expiresIn (lazy 401 refresh only)")
        void nestedResponsePathNonStandardExpiryIsNull() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://business-api.tiktok.com/portal/auth",
                      "tokenUrl": "https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/",
                      "tokenResponsePath": "data",
                      "scopes": ["ad.read"]
                    }
                    """));
            // TikTok Business names expiry access_token_expires_in, which the engine does not read.
            JsonNode body = json.readTree("""
                    {"code":0,"data":{"access_token":"AT","access_token_expires_in":86400}}
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body, cfg);

            assertThat(resp.accessToken()).isEqualTo("AT");
            assertThat(resp.expiresIn()).isNull();
        }

        @Test
        @DisplayName("tokenResponsePath: missing wrapper object throws (not a silent null token)")
        void nestedResponsePathMissingWrapper() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://business-api.tiktok.com/portal/auth",
                      "tokenUrl": "https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/",
                      "tokenResponsePath": "data",
                      "scopes": ["ad.read"]
                    }
                    """));
            JsonNode body = json.readTree("""
                    {"code":40105,"message":"invalid auth_code"}
                    """);

            assertThatThrownBy(() -> engine.parseTokenResponse(body, cfg))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("data");
        }

        @Test
        @DisplayName("no tokenResponsePath (default): config overload reads the top level unchanged")
        void configOverloadRootUnchanged() throws Exception {
            OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                    {
                      "authorizationUrl": "https://example.com/a",
                      "tokenUrl": "https://example.com/t",
                      "scopes": ["read"]
                    }
                    """));
            JsonNode body = json.readTree("""
                    {"access_token":"AT","token_type":"Bearer"}
                    """);

            assertThat(engine.parseTokenResponse(body, cfg).accessToken()).isEqualTo("AT");
        }

        @Test
        @DisplayName("missing refresh_token → null (provider did not issue one)")
        void missingRefreshToken() throws Exception {
            JsonNode body = json.readTree("""
                    {"access_token":"AT","token_type":"Bearer","expires_in":3600}
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body);

            assertThat(resp.accessToken()).isEqualTo("AT");
            assertThat(resp.refreshToken()).isNull();
        }

        @Test
        @DisplayName("missing expires_in → null (long-lived token like Shopify/Notion)")
        void missingExpiresIn() throws Exception {
            JsonNode body = json.readTree("""
                    {"access_token":"AT","token_type":"Bearer"}
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body);

            assertThat(resp.expiresIn()).isNull();
        }

        @Test
        @DisplayName("default token_type is Bearer when provider omits it")
        void defaultTokenType() throws Exception {
            JsonNode body = json.readTree("""
                    {"access_token":"AT"}
                    """);

            assertThat(engine.parseTokenResponse(body).tokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("missing access_token → IllegalStateException")
        void missingAccessToken() throws Exception {
            JsonNode body = json.readTree("""
                    {"token_type":"Bearer","expires_in":3600}
                    """);

            assertThatThrownBy(() -> engine.parseTokenResponse(body))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing access_token");
        }

        @Test
        @DisplayName("null body → IllegalStateException")
        void nullBody() {
            assertThatThrownBy(() -> engine.parseTokenResponse(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty body");
        }

        @Test
        @DisplayName("blank (whitespace-only) access_token → IllegalStateException, same as missing")
        void blankAccessToken() throws Exception {
            // present-but-blank is a distinct branch from absent: line 170 rejects isBlank() too,
            // so a "   " token must be treated as missing, never returned as a usable access_token.
            JsonNode body = json.readTree("""
                    {"access_token":"   ","token_type":"Bearer","expires_in":3600}
                    """);

            assertThatThrownBy(() -> engine.parseTokenResponse(body))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing access_token");
        }

        @Test
        @DisplayName("blank (whitespace-only) refresh_token → null, same as missing")
        void blankRefreshToken() throws Exception {
            // textOrNull collapses a present-but-blank refresh_token to null, so callers never
            // persist a whitespace string as a real refresh token.
            JsonNode body = json.readTree("""
                    {"access_token":"AT","refresh_token":"   ","token_type":"Bearer","expires_in":3600}
                    """);

            OAuth2TokenResponse resp = engine.parseTokenResponse(body);

            assertThat(resp.accessToken()).isEqualTo("AT");
            assertThat(resp.refreshToken()).isNull();
        }
    }

    // ─────────────────── shouldUsePkce ───────────────────

    @Test
    @DisplayName("shouldUsePkce reflects config.pkceEnabled")
    void shouldUsePkceReflectsConfig() {
        assertThat(engine.shouldUsePkce(standardConfig(true))).isTrue();
        assertThat(engine.shouldUsePkce(standardConfig(false))).isFalse();
    }

    // ─────────────────── extractQuirkFields ───────────────────

    @Nested
    @DisplayName("extractQuirkFields")
    class ExtractQuirkFields {

        @Test
        @DisplayName("no quirks declared → empty map (plain RFC provider)")
        void noQuirks() throws Exception {
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"access_token": "a", "instance_url": "https://acme.my.salesforce.com"}
                    """);
            assertThat(engine.extractQuirkFields(body, standardConfig(false))).isEmpty();
        }

        @Test
        @DisplayName("salesforce quirk: harvests instance_url from token response")
        void salesforceInstanceUrl() throws Exception {
            OAuth2ProviderConfig cfg = withQuirks(new OAuth2ProviderConfig.RefreshQuirks(
                    "instance_url", null, null, null));
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"access_token": "a", "instance_url": "https://acme.my.salesforce.com"}
                    """);

            assertThat(engine.extractQuirkFields(body, cfg))
                    .containsExactly(java.util.Map.entry("instance_url", "https://acme.my.salesforce.com"));
        }

        @Test
        @DisplayName("all four quirks harvested when present")
        void allQuirksHarvested() throws Exception {
            OAuth2ProviderConfig cfg = withQuirks(new OAuth2ProviderConfig.RefreshQuirks(
                    "instance_url", "region", "tenant_id", "realmId"));
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {
                      "access_token": "a",
                      "instance_url": "https://acme.my.salesforce.com",
                      "region": "eu",
                      "tenant_id": "tenant-xyz",
                      "realmId": "realm-123"
                    }
                    """);

            assertThat(engine.extractQuirkFields(body, cfg))
                    .containsEntry("instance_url", "https://acme.my.salesforce.com")
                    .containsEntry("region", "eu")
                    .containsEntry("tenant_id", "tenant-xyz")
                    .containsEntry("realmId", "realm-123");
        }

        @Test
        @DisplayName("missing quirk field in response → skipped, no null values in map")
        void missingQuirkFieldSkipped() throws Exception {
            OAuth2ProviderConfig cfg = withQuirks(new OAuth2ProviderConfig.RefreshQuirks(
                    "instance_url", "region", null, null));
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"access_token": "a", "instance_url": "https://x"}
                    """);

            java.util.Map<String, String> out = engine.extractQuirkFields(body, cfg);

            assertThat(out).containsOnlyKeys("instance_url");
        }

        private OAuth2ProviderConfig withQuirks(OAuth2ProviderConfig.RefreshQuirks q) {
            return new OAuth2ProviderConfig(
                    "https://a", "https://t", null,
                    List.of("read"), " ", AuthMethod.POST, false, Map.of(),
                    new OAuth2ProviderConfig.RefreshConfig(true, false, null, null, q)
            );
        }
    }

    // ─────────────────── helpers ───────────────────

    private static OAuth2ProviderConfig standardConfig(boolean pkce) {
        return new OAuth2ProviderConfig(
                "https://example.com/authorize",
                "https://example.com/token",
                null,
                List.of("read"),
                " ",
                AuthMethod.POST,
                pkce,
                Map.of(),
                OAuth2ProviderConfig.RefreshConfig.STANDARD
        );
    }

    /** The form-encoded token body of a request (all non-JSON providers). */
    @SuppressWarnings("unchecked")
    private static MultiValueMap<String, String> form(HttpEntity<?> req) {
        return (MultiValueMap<String, String>) req.getBody();
    }

    private static Map<String, String> parseQuery(String url) {
        int q = url.indexOf('?');
        assertThat(q).as("URL should have a query string: %s", url).isGreaterThan(-1);
        String query = url.substring(q + 1);
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
