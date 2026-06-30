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
