package com.apimarketplace.auth.credential.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2ProviderConfig.fromJson")
class OAuth2ProviderConfigTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("parses minimal config with RFC defaults")
    void minimalConfig() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/authorize",
                  "tokenUrl": "https://example.com/token",
                  "scopes": ["read", "write"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.authorizationUrl()).isEqualTo("https://example.com/authorize");
        assertThat(cfg.tokenUrl()).isEqualTo("https://example.com/token");
        assertThat(cfg.scopes()).containsExactly("read", "write");
        assertThat(cfg.scopeDelimiter()).isEqualTo(" ");
        assertThat(cfg.tokenAuthMethod()).isEqualTo(OAuth2ProviderConfig.AuthMethod.POST);
        assertThat(cfg.tokenParamsLocation()).isEqualTo(OAuth2ProviderConfig.TokenParamsLocation.FORM);
        assertThat(cfg.pkceEnabled()).isFalse();
        assertThat(cfg.authorizeExtraParams()).isEmpty();
        assertThat(cfg.effectiveRefreshUrl()).isEqualTo("https://example.com/token");
        assertThat(cfg.grantType()).isEqualTo("authorizationCode");
        assertThat(cfg.isClientCredentials()).isFalse();
        assertThat(cfg.clientIdParam()).isEqualTo("client_id");
    }

    @Test
    @DisplayName("parses a provider-renamed client id param (TikTok client_key)")
    void customClientIdParam() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://www.tiktok.com/v2/auth/authorize/",
                  "tokenUrl": "https://open.tiktokapis.com/v2/oauth/token/",
                  "clientIdParam": "client_key",
                  "scopes": ["user.info.basic"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.clientIdParam()).isEqualTo("client_key");
    }

    @Test
    @DisplayName("minimal config gets RFC-standard tokenExchange defaults")
    void tokenExchangeDefaults() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/a",
                  "tokenUrl": "https://example.com/t",
                  "scopes": ["read"]
                }
                """);

        OAuth2ProviderConfig.TokenExchangeConfig tx = OAuth2ProviderConfig.fromJson(node).tokenExchange();

        assertThat(tx.clientSecretParam()).isEqualTo("client_secret");
        assertThat(tx.codeParam()).isEqualTo("code");
        assertThat(tx.requestFormat())
                .isEqualTo(OAuth2ProviderConfig.TokenExchangeConfig.RequestFormat.FORM);
        assertThat(tx.responsePath()).isNull();
    }

    @Test
    @DisplayName("parses the non-RFC TikTok-Business token-exchange quirks (app_id/secret/auth_code/json/data)")
    void tokenExchangeBusinessQuirks() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://business-api.tiktok.com/portal/auth",
                  "tokenUrl": "https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/",
                  "clientIdParam": "app_id",
                  "clientSecretParam": "secret",
                  "codeParam": "auth_code",
                  "tokenRequestFormat": "json",
                  "tokenResponsePath": "data",
                  "scopes": ["ad.read"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg.clientIdParam()).isEqualTo("app_id");
        assertThat(cfg.tokenExchange().clientSecretParam()).isEqualTo("secret");
        assertThat(cfg.tokenExchange().codeParam()).isEqualTo("auth_code");
        assertThat(cfg.tokenExchange().requestFormat())
                .isEqualTo(OAuth2ProviderConfig.TokenExchangeConfig.RequestFormat.JSON);
        assertThat(cfg.tokenExchange().responsePath()).isEqualTo("data");
        // Copy methods must preserve the whole quirk record.
        assertThat(cfg.withScopes(java.util.List.of("campaign.read")).tokenExchange().codeParam())
                .isEqualTo("auth_code");
    }

    @Test
    @DisplayName("blank clientIdParam falls back to the RFC client_id default")
    void blankClientIdParamDefaults() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/a",
                  "tokenUrl": "https://example.com/t",
                  "clientIdParam": "   ",
                  "scopes": ["read"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg.clientIdParam()).isEqualTo("client_id");
    }

    @Test
    @DisplayName("withScopes and withUrls preserve a renamed clientIdParam")
    void copyMethodsPreserveClientIdParam() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://www.tiktok.com/v2/auth/authorize/",
                  "tokenUrl": "https://open.tiktokapis.com/v2/oauth/token/",
                  "clientIdParam": "client_key",
                  "scopes": ["user.info.basic"]
                }
                """);
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        // Both copy helpers must carry clientIdParam through - the record's own Javadoc warns that a
        // new field is easy to silently drop when only some components are copied.
        assertThat(cfg.withScopes(java.util.List.of("video.upload")).clientIdParam())
                .isEqualTo("client_key");
        assertThat(cfg.withUrls("https://new/auth", "https://new/token").clientIdParam())
                .isEqualTo("client_key");
    }

    @Test
    @DisplayName("parses full config: pkce, authMethod, scopeDelimiter, extras")
    void fullConfig() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/a",
                  "tokenUrl": "https://example.com/t",
                  "refreshUrl": "https://example.com/refresh",
                  "scopes": ["chat:write", "users:read"],
                  "scopeDelimiter": ",",
                  "authMethod": "client_secret_basic",
                  "tokenParamsLocation": "query",
                  "pkce": true,
                  "authorizeExtraParams": { "audience": "api.atlassian.com", "prompt": "login" }
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.effectiveRefreshUrl()).isEqualTo("https://example.com/refresh");
        assertThat(cfg.scopeDelimiter()).isEqualTo(",");
        assertThat(cfg.joinedScopes()).isEqualTo("chat:write,users:read");
        assertThat(cfg.tokenAuthMethod()).isEqualTo(OAuth2ProviderConfig.AuthMethod.BASIC);
        assertThat(cfg.tokenParamsLocation()).isEqualTo(OAuth2ProviderConfig.TokenParamsLocation.QUERY);
        assertThat(cfg.pkceEnabled()).isTrue();
        assertThat(cfg.authorizeExtraParams())
                .containsEntry("audience", "api.atlassian.com")
                .containsEntry("prompt", "login");
    }

    @Test
    @DisplayName("authMethod aliases map correctly")
    void authMethodAliases() throws Exception {
        assertThat(authMethodOf("client_secret_post"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.POST);
        assertThat(authMethodOf("client_secret_basic"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.BASIC);
        assertThat(authMethodOf("basic"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.BASIC);
        assertThat(authMethodOf("none"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.NONE);
        assertThat(authMethodOf("public"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.NONE);
        // Unknown → safe default.
        assertThat(authMethodOf("weird-new-spec"))
                .isEqualTo(OAuth2ProviderConfig.AuthMethod.POST);
    }

    @Test
    @DisplayName("legacy scopes as single space-delimited string")
    void scopesAsString() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/a",
                  "tokenUrl": "https://example.com/t",
                  "scopes": "read write admin"
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);
        assertThat(cfg.scopes()).containsExactly("read", "write", "admin");
    }

    @Test
    @DisplayName("missing required fields → null")
    void missingRequiredFields() throws Exception {
        assertThat(OAuth2ProviderConfig.fromJson(null)).isNull();
        assertThat(OAuth2ProviderConfig.fromJson(json.readTree("{}"))).isNull();
        assertThat(OAuth2ProviderConfig.fromJson(json.readTree("""
                {"authorizationUrl":"https://a"}
                """))).isNull();
        assertThat(OAuth2ProviderConfig.fromJson(json.readTree("""
                {"tokenUrl":"https://t"}
                """))).isNull();
    }

    @Test
    @DisplayName("client_credentials providers do not require authorizationUrl")
    void clientCredentialsDoesNotRequireAuthorizationUrl() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "grantType": "client_credentials",
                  "tokenUrl": "https://example.com/token",
                  "scopes": [],
                  "refresh": {
                    "supported": false,
                    "reason": "client_credentials flow"
                  }
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.authorizationUrl()).isNull();
        assertThat(cfg.tokenUrl()).isEqualTo("https://example.com/token");
        assertThat(cfg.grantType()).isEqualTo("client_credentials");
        assertThat(cfg.isClientCredentials()).isTrue();
        assertThat(cfg.refresh().supported()).isFalse();
    }

    @Test
    @DisplayName("refresh block absent → defaults to STANDARD (supported, no rotation, no TTL)")
    void refreshMissingDefaultsToStandard() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": []
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg.refresh()).isEqualTo(OAuth2ProviderConfig.RefreshConfig.STANDARD);
        assertThat(cfg.refresh().supported()).isTrue();
        assertThat(cfg.refresh().rotatesRefreshToken()).isFalse();
        assertThat(cfg.refresh().refreshTokenTtlDays()).isNull();
        assertThat(cfg.refresh().unsupportedReason()).isNull();
        assertThat(cfg.refresh().quirks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("parses google-style refresh: supported, no rotation, ttl=180")
    void refreshGoogleStyle() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "refresh": {
                    "supported": true,
                    "rotatesRefreshToken": false,
                    "refreshTokenTtlDays": 180
                  }
                }
                """);

        OAuth2ProviderConfig.RefreshConfig r = OAuth2ProviderConfig.fromJson(node).refresh();

        assertThat(r.supported()).isTrue();
        assertThat(r.rotatesRefreshToken()).isFalse();
        assertThat(r.refreshTokenTtlDays()).isEqualTo(180);
        assertThat(r.unsupportedReason()).isNull();
    }

    @Test
    @DisplayName("parses rotating provider: rotatesRefreshToken=true + ttl=60 (xero-style)")
    void refreshRotatingWithTtl() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "refresh": {
                    "supported": true,
                    "rotatesRefreshToken": true,
                    "refreshTokenTtlDays": 60,
                    "quirks": { "tenantIdField": "tenant_id" }
                  }
                }
                """);

        OAuth2ProviderConfig.RefreshConfig r = OAuth2ProviderConfig.fromJson(node).refresh();

        assertThat(r.rotatesRefreshToken()).isTrue();
        assertThat(r.refreshTokenTtlDays()).isEqualTo(60);
        assertThat(r.quirks().tenantIdField()).isEqualTo("tenant_id");
        assertThat(r.quirks().instanceUrlField()).isNull();
    }

    @Test
    @DisplayName("parses all four quirk fields (belt-and-suspenders, never hit in real JSON)")
    void refreshAllQuirks() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "refresh": {
                    "supported": true,
                    "rotatesRefreshToken": false,
                    "quirks": {
                      "instanceUrlField": "instance_url",
                      "regionField": "region",
                      "tenantIdField": "tenant_id",
                      "realmIdField": "realmId"
                    }
                  }
                }
                """);

        OAuth2ProviderConfig.RefreshQuirks q = OAuth2ProviderConfig.fromJson(node).refresh().quirks();

        assertThat(q.instanceUrlField()).isEqualTo("instance_url");
        assertThat(q.regionField()).isEqualTo("region");
        assertThat(q.tenantIdField()).isEqualTo("tenant_id");
        assertThat(q.realmIdField()).isEqualTo("realmId");
        assertThat(q.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("parses unsupported refresh: supported=false + reason, all other fields null")
    void refreshUnsupported() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "refresh": {
                    "supported": false,
                    "reason": "client_credentials flow - no refresh token issued"
                  }
                }
                """);

        OAuth2ProviderConfig.RefreshConfig r = OAuth2ProviderConfig.fromJson(node).refresh();

        assertThat(r.supported()).isFalse();
        assertThat(r.unsupportedReason()).isEqualTo("client_credentials flow - no refresh token issued");
        assertThat(r.rotatesRefreshToken()).isFalse();
        assertThat(r.refreshTokenTtlDays()).isNull();
        assertThat(r.quirks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("RefreshConfig.unsupported factory produces expected shape")
    void refreshUnsupportedFactory() {
        OAuth2ProviderConfig.RefreshConfig r =
                OAuth2ProviderConfig.RefreshConfig.unsupported("long-lived PAT");

        assertThat(r.supported()).isFalse();
        assertThat(r.unsupportedReason()).isEqualTo("long-lived PAT");
        assertThat(r.quirks()).isEqualTo(OAuth2ProviderConfig.RefreshQuirks.NONE);
    }

    @Test
    @DisplayName("joinedScopes respects the configured delimiter")
    void joinedScopesRespectsDelimiter() {
        OAuth2ProviderConfig cfg = new OAuth2ProviderConfig(
                "https://a", "https://t", null,
                java.util.List.of("a", "b", "c"),
                ",", OAuth2ProviderConfig.AuthMethod.POST, false, java.util.Map.of(),
                OAuth2ProviderConfig.RefreshConfig.STANDARD);
        assertThat(cfg.joinedScopes()).isEqualTo("a,b,c");
    }

    @Test
    @DisplayName("parses the Slack user-scope family and splits the scope list by membership")
    void parsesUserScopeFamily() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read", "channels:read"],
                  "userScopeParam": "user_scope",
                  "userScopes": ["search:read", "stars:read"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.userScopes().param()).isEqualTo("user_scope");
        // Split by membership, so only the family members actually being requested move over:
        // "stars:read" is declared but not requested here and must appear in neither list.
        assertThat(cfg.primaryScopes()).containsExactly("chat:write", "channels:read");
        assertThat(cfg.requestedUserScopes()).containsExactly("search:read");
        // joinedScopes stays the record of everything requested, both families together.
        assertThat(cfg.joinedScopes()).isEqualTo("chat:write search:read channels:read");
    }

    @Test
    @DisplayName("userScopes with no userScopeParam DROPS its members from the request, parsed from JSON like production does")
    void userScopesWithoutParamAreDroppedNotFallenBack() throws Exception {
        // The path that matters: fromJson is what every production config goes through, seed and
        // signed catalog bundle alike. Collapsing to NONE here would put "search:read" back into
        // the `scope` parameter, which is the shape that made Slack refuse every installation.
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopes": ["search:read"]
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.primaryScopes())
                .as("a declared member never falls back into the bot parameter")
                .containsExactly("chat:write");
        assertThat(cfg.requestedUserScopes())
                .as("and there is no parameter to send it through")
                .isEmpty();
        assertThat(cfg.unroutableUserScopes())
                .as("so the drop is reported instead of being silent")
                .containsExactly("search:read");
    }

    @Test
    @DisplayName("userScopeParam with no members parses to NONE: nothing to route, behaviour unchanged")
    void userScopeParamWithoutMembersIsNone() throws Exception {
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "user_scope"
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.userScopes()).isEqualTo(OAuth2ProviderConfig.UserScopeConfig.NONE);
        assertThat(cfg.primaryScopes()).containsExactly("chat:write", "search:read");
        assertThat(cfg.requestedUserScopes()).isEmpty();
        assertThat(cfg.unroutableUserScopes()).isEmpty();
    }

    @Test
    @DisplayName("a non-array userScopes does not route, and its members are dropped rather than left in scope")
    void userScopesAsAStringDoesNotRouteAndIsNotFallenBackEither() throws Exception {
        // `scopes` tolerates a legacy space-delimited string; validate_apis.py refuses it for
        // userScopes, so routing it here would make the two layers disagree on what a valid seed
        // is. But simply ignoring the declaration would put "search:read" back in the bot
        // parameter, which is the outage shape - and the seed validator cannot protect the
        // catalog-bundle path. So: not routed, and not fallen back either.
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "user_scope",
                  "userScopes": "search:read"
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.primaryScopes()).containsExactly("chat:write");
        assertThat(cfg.requestedUserScopes()).isEmpty();
        assertThat(cfg.unroutableUserScopes()).containsExactly("search:read");
    }

    @Test
    @DisplayName("a blank userScopeParam drops the members too, parsed from JSON like production does")
    void blankUserScopeParamDropsMembersThroughFromJson() throws Exception {
        // The hand-built record covers a null param; this covers the shape a seed can actually
        // contain. textOrNull collapses a blank to null, so the two must agree.
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "   ",
                  "userScopes": ["search:read"]
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.primaryScopes()).containsExactly("chat:write");
        assertThat(cfg.requestedUserScopes()).isEmpty();
        assertThat(cfg.unroutableUserScopes()).containsExactly("search:read");
    }

    @Test
    @DisplayName("an empty userScopes array is no family at all: every scope stays in the bot parameter")
    void emptyUserScopesArrayIsNone() throws Exception {
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "user_scope",
                  "userScopes": []
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.userScopes()).isEqualTo(OAuth2ProviderConfig.UserScopeConfig.NONE);
        assertThat(cfg.primaryScopes()).containsExactly("chat:write", "search:read");
        assertThat(cfg.unroutableUserScopes()).isEmpty();
    }

    @Test
    @DisplayName("non-string entries in userScopes become phantom members that route nothing, and never leak a real one")
    void nonStringUserScopeEntriesAreHarmlessPhantoms() throws Exception {
        // Jackson coerces rather than skipping, so 42 arrives as the member "42". The validator
        // refuses the shape; what matters here is that the coercion cannot push a REAL member back
        // into the bot parameter, which is the only outcome that would break a connect.
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "user_scope",
                  "userScopes": ["search:read", 42, null]
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.primaryScopes()).containsExactly("chat:write");
        assertThat(cfg.requestedUserScopes()).containsExactly("search:read");
        assertThat(cfg.userScopes().scopes()).contains("42");
    }

    @Test
    @DisplayName("a userScopes shape with no readable member is the one case that cannot fail closed, and it is inert")
    void userScopesWithNoReadableMemberIsInert() throws Exception {
        // An object or a number identifies no scope, so there is nothing to hold out of `scope`:
        // this shape necessarily behaves as if no family were declared. Pinned so the limit is a
        // known boundary rather than a surprise, and so the seed validator stays the only guard.
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write", "search:read"],
                  "userScopeParam": "user_scope",
                  "userScopes": {"search": "read"}
                }
                """));

        assertThat(cfg).isNotNull();
        assertThat(cfg.userScopes()).isEqualTo(OAuth2ProviderConfig.UserScopeConfig.NONE);
        assertThat(cfg.primaryScopes()).containsExactly("chat:write", "search:read");
        assertThat(cfg.unroutableUserScopes()).isEmpty();
    }

    @Test
    @DisplayName("a provider declaring no family keeps every scope in the primary list")
    void absentUserScopeFamilyIsANoOp() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://example.com/a",
                  "tokenUrl": "https://example.com/t",
                  "scopes": ["read", "write"]
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        assertThat(cfg.userScopes()).isEqualTo(OAuth2ProviderConfig.UserScopeConfig.NONE);
        assertThat(cfg.primaryScopes()).isEqualTo(cfg.scopes());
        assertThat(cfg.joinedPrimaryScopes()).isEqualTo(cfg.joinedScopes());
    }

    @Test
    @DisplayName("withScopes and withUrls carry the user-scope family through (BYOK widening must not lose the routing)")
    void copyHelpersPreserveUserScopeFamily() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://slack.com/oauth/v2/authorize",
                  "tokenUrl": "https://slack.com/api/oauth.v2.access",
                  "scopes": ["chat:write"],
                  "userScopeParam": "user_scope",
                  "userScopes": ["search:read"]
                }
                """);
        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        // This is exactly the BYOK path: OAuth2Service widens the scope list to
        // scopes + byokOnlyScopes. If the family were dropped here, "search:read" would land
        // back in the plain `scope` parameter and Slack would refuse the install.
        OAuth2ProviderConfig widened = cfg.withScopes(java.util.List.of("chat:write", "search:read"));
        assertThat(widened.requestedUserScopes()).containsExactly("search:read");
        assertThat(widened.primaryScopes()).containsExactly("chat:write");

        OAuth2ProviderConfig rehosted = widened.withUrls("https://slack.com/oauth/v2/authorize", null);
        assertThat(rehosted.userScopes()).isEqualTo(widened.userScopes());
    }

    @Test
    @DisplayName("parses longLivedExchange and refresh.accessTokenGrant (Meta family)")
    void metaAccessTokenGrantBlocks() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://www.instagram.com/oauth/authorize",
                  "tokenUrl": "https://api.instagram.com/oauth/access_token",
                  "scopes": ["instagram_business_basic"],
                  "longLivedExchange": {
                    "url": "https://graph.instagram.com/access_token",
                    "grantType": "ig_exchange_token",
                    "tokenParam": "access_token",
                    "sendClientId": false,
                    "sendClientSecret": true
                  },
                  "refresh": {
                    "supported": true,
                    "accessTokenGrant": {
                      "url": "https://graph.instagram.com/refresh_access_token",
                      "grantType": "ig_refresh_token"
                    }
                  }
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg).isNotNull();
        OAuth2ProviderConfig.AccessTokenGrant exchange = cfg.longLivedExchange();
        assertThat(exchange).isNotNull();
        assertThat(exchange.url()).isEqualTo("https://graph.instagram.com/access_token");
        assertThat(exchange.grantType()).isEqualTo("ig_exchange_token");
        assertThat(exchange.sendClientId()).isFalse();
        assertThat(exchange.sendClientSecret()).isTrue();

        OAuth2ProviderConfig.AccessTokenGrant renewal = cfg.refresh().accessTokenGrant();
        assertThat(renewal).isNotNull();
        assertThat(renewal.url()).isEqualTo("https://graph.instagram.com/refresh_access_token");
        assertThat(renewal.grantType()).isEqualTo("ig_refresh_token");
        // Omitted fields fall back: tokenParam=access_token, both send flags false.
        assertThat(renewal.tokenParam()).isEqualTo("access_token");
        assertThat(renewal.sendClientId()).isFalse();
        assertThat(renewal.sendClientSecret()).isFalse();
        assertThat(cfg.refresh().supported()).isTrue();
    }

    @Test
    @DisplayName("incomplete accessTokenGrant blocks degrade to null (feature off), never half-configured")
    void incompleteAccessTokenGrantIsNull() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "longLivedExchange": { "url": "https://graph.instagram.com/access_token" },
                  "refresh": {
                    "supported": true,
                    "accessTokenGrant": { "grantType": "ig_refresh_token" }
                  }
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg.longLivedExchange()).as("missing grantType => block off").isNull();
        assertThat(cfg.refresh().accessTokenGrant()).as("missing url => block off").isNull();
    }

    @Test
    @DisplayName("providers without the Meta blocks parse with both absent (RFC providers untouched)")
    void absentAccessTokenGrantBlocks() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "refresh": { "supported": true }
                }
                """);

        OAuth2ProviderConfig cfg = OAuth2ProviderConfig.fromJson(node);

        assertThat(cfg.longLivedExchange()).isNull();
        assertThat(cfg.refresh().accessTokenGrant()).isNull();
    }

    private OAuth2ProviderConfig.AuthMethod authMethodOf(String raw) throws Exception {
        JsonNode node = json.readTree("""
                {
                  "authorizationUrl": "https://a",
                  "tokenUrl": "https://t",
                  "scopes": [],
                  "authMethod": "%s"
                }
                """.formatted(raw));
        return OAuth2ProviderConfig.fromJson(node).tokenAuthMethod();
    }
}
