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
