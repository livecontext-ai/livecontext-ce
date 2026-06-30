package com.apimarketplace.auth.credential.domain;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlatformCredentialModels Tests")
class PlatformCredentialModelsTest {

    // ========== AuthType Tests ==========

    @Nested
    @DisplayName("AuthType.fromValue")
    class AuthTypeFromValueTests {

        @Test
        @DisplayName("should return OAUTH2 for 'oauth2'")
        void fromValue_oauth2() {
            assertThat(AuthType.fromValue("oauth2")).isEqualTo(AuthType.OAUTH2);
        }

        @Test
        @DisplayName("should return API_KEY for 'api_key'")
        void fromValue_apiKey() {
            assertThat(AuthType.fromValue("api_key")).isEqualTo(AuthType.API_KEY);
        }

        @Test
        @DisplayName("should return BASIC for 'basic'")
        void fromValue_basic() {
            assertThat(AuthType.fromValue("basic")).isEqualTo(AuthType.BASIC);
        }

        @Test
        @DisplayName("should return BEARER for 'bearer'")
        void fromValue_bearer() {
            assertThat(AuthType.fromValue("bearer")).isEqualTo(AuthType.BEARER);
        }

        @Test
        @DisplayName("should return CUSTOM for 'custom'")
        void fromValue_custom() {
            assertThat(AuthType.fromValue("custom")).isEqualTo(AuthType.CUSTOM);
        }

        @Test
        @DisplayName("should return NONE for 'none'")
        void fromValue_none() {
            assertThat(AuthType.fromValue("none")).isEqualTo(AuthType.NONE);
        }

        @Test
        @DisplayName("should resolve alias 'apikey' to API_KEY")
        void fromValue_aliasApikey() {
            assertThat(AuthType.fromValue("apikey")).isEqualTo(AuthType.API_KEY);
        }

        @Test
        @DisplayName("should resolve alias 'basic_auth' to BASIC")
        void fromValue_aliasBasicAuth() {
            assertThat(AuthType.fromValue("basic_auth")).isEqualTo(AuthType.BASIC);
        }

        @Test
        @DisplayName("should resolve alias 'bearer_token' to BEARER")
        void fromValue_aliasBearerToken() {
            assertThat(AuthType.fromValue("bearer_token")).isEqualTo(AuthType.BEARER);
        }

        @Test
        @DisplayName("should resolve alias 'aws_signature_v4' to CUSTOM")
        void fromValue_aliasAwsSignature() {
            assertThat(AuthType.fromValue("aws_signature_v4")).isEqualTo(AuthType.CUSTOM);
        }

        @Test
        @DisplayName("should return OAUTH2 for null")
        void fromValue_null() {
            assertThat(AuthType.fromValue(null)).isEqualTo(AuthType.OAUTH2);
        }

        @Test
        @DisplayName("should return CUSTOM for unknown value")
        void fromValue_unknown() {
            assertThat(AuthType.fromValue("some_unknown_type")).isEqualTo(AuthType.CUSTOM);
        }

        @Test
        @DisplayName("should be case insensitive")
        void fromValue_caseInsensitive() {
            assertThat(AuthType.fromValue("OAUTH2")).isEqualTo(AuthType.OAUTH2);
            assertThat(AuthType.fromValue("Api_Key")).isEqualTo(AuthType.API_KEY);
            assertThat(AuthType.fromValue("BASIC")).isEqualTo(AuthType.BASIC);
            assertThat(AuthType.fromValue("Bearer")).isEqualTo(AuthType.BEARER);
            assertThat(AuthType.fromValue("APIKEY")).isEqualTo(AuthType.API_KEY);
            assertThat(AuthType.fromValue("Basic_Auth")).isEqualTo(AuthType.BASIC);
        }

        @Test
        @DisplayName("should return correct value for each enum constant")
        void getValue_allEnums() {
            assertThat(AuthType.OAUTH2.getValue()).isEqualTo("oauth2");
            assertThat(AuthType.API_KEY.getValue()).isEqualTo("api_key");
            assertThat(AuthType.BASIC.getValue()).isEqualTo("basic");
            assertThat(AuthType.BEARER.getValue()).isEqualTo("bearer");
            assertThat(AuthType.CUSTOM.getValue()).isEqualTo("custom");
            assertThat(AuthType.NONE.getValue()).isEqualTo("none");
        }
    }

    // ========== PlatformCredential Tests ==========

    @Nested
    @DisplayName("PlatformCredential")
    class PlatformCredentialTests {

        @Test
        @DisplayName("hasOAuth2Credentials should return true when both clientId and clientSecret are non-blank")
        void hasOAuth2Credentials_true() {
            PlatformCredential cred = buildCredential("my-client-id", "my-client-secret", null, null, null);
            assertThat(cred.hasOAuth2Credentials()).isTrue();
        }

        @Test
        @DisplayName("hasOAuth2Credentials should return false when clientId is null")
        void hasOAuth2Credentials_falseNullClientId() {
            PlatformCredential cred = buildCredential(null, "my-client-secret", null, null, null);
            assertThat(cred.hasOAuth2Credentials()).isFalse();
        }

        @Test
        @DisplayName("hasOAuth2Credentials should return false when clientSecret is null")
        void hasOAuth2Credentials_falseNullClientSecret() {
            PlatformCredential cred = buildCredential("my-client-id", null, null, null, null);
            assertThat(cred.hasOAuth2Credentials()).isFalse();
        }

        @Test
        @DisplayName("hasOAuth2Credentials should return false when clientId is blank")
        void hasOAuth2Credentials_falseBlankClientId() {
            PlatformCredential cred = buildCredential("  ", "my-client-secret", null, null, null);
            assertThat(cred.hasOAuth2Credentials()).isFalse();
        }

        @Test
        @DisplayName("hasOAuth2Credentials should return false when clientSecret is blank")
        void hasOAuth2Credentials_falseBlankClientSecret() {
            PlatformCredential cred = buildCredential("my-client-id", "", null, null, null);
            assertThat(cred.hasOAuth2Credentials()).isFalse();
        }

        @Test
        @DisplayName("hasApiKey should return true when apiKey is non-blank")
        void hasApiKey_true() {
            PlatformCredential cred = buildCredential(null, null, "my-api-key", null, null);
            assertThat(cred.hasApiKey()).isTrue();
        }

        @Test
        @DisplayName("hasApiKey should return false when apiKey is null")
        void hasApiKey_falseNull() {
            PlatformCredential cred = buildCredential(null, null, null, null, null);
            assertThat(cred.hasApiKey()).isFalse();
        }

        @Test
        @DisplayName("hasApiKey should return false when apiKey is blank")
        void hasApiKey_falseBlank() {
            PlatformCredential cred = buildCredential(null, null, "  ", null, null);
            assertThat(cred.hasApiKey()).isFalse();
        }

        @Test
        @DisplayName("hasBasicAuth should return true when both username and password are non-blank")
        void hasBasicAuth_true() {
            PlatformCredential cred = buildCredential(null, null, null, "user", "pass");
            assertThat(cred.hasBasicAuth()).isTrue();
        }

        @Test
        @DisplayName("hasBasicAuth should return false when username is null")
        void hasBasicAuth_falseNullUsername() {
            PlatformCredential cred = buildCredential(null, null, null, null, "pass");
            assertThat(cred.hasBasicAuth()).isFalse();
        }

        @Test
        @DisplayName("hasBasicAuth should return false when password is null")
        void hasBasicAuth_falseNullPassword() {
            PlatformCredential cred = buildCredential(null, null, null, "user", null);
            assertThat(cred.hasBasicAuth()).isFalse();
        }

        @Test
        @DisplayName("hasBasicAuth should return false when username is blank")
        void hasBasicAuth_falseBlankUsername() {
            PlatformCredential cred = buildCredential(null, null, null, " ", "pass");
            assertThat(cred.hasBasicAuth()).isFalse();
        }

        @Test
        @DisplayName("hasBasicAuth should return false when password is blank")
        void hasBasicAuth_falseBlankPassword() {
            PlatformCredential cred = buildCredential(null, null, null, "user", "");
            assertThat(cred.hasBasicAuth()).isFalse();
        }

        @Test
        @DisplayName("withId should return new instance with updated id")
        void withId_returnsNewInstance() {
            PlatformCredential original = buildCredential("cid", "csec", null, null, null);
            PlatformCredential updated = original.withId(42L);

            assertThat(updated.id()).isEqualTo(42L);
            assertThat(updated.integrationName()).isEqualTo(original.integrationName());
            assertThat(updated.clientId()).isEqualTo(original.clientId());
        }
    }

    // ========== PlatformCredentialResponse Tests ==========

    @Nested
    @DisplayName("PlatformCredentialResponse.from")
    class PlatformCredentialResponseTests {

        @Test
        @DisplayName("should mask clientId correctly for long client IDs")
        void from_masksClientIdLong() {
            PlatformCredential cred = buildCredential("abcdefghijklmnop", "secret", null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.clientIdMasked()).isEqualTo("abcd****mnop");
        }

        @Test
        @DisplayName("should mask clientId as **** for short client IDs")
        void from_masksClientIdShort() {
            PlatformCredential cred = buildCredential("short", "secret", null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.clientIdMasked()).isEqualTo("****");
        }

        @Test
        @DisplayName("should mask clientId as **** for null client IDs")
        void from_masksClientIdNull() {
            PlatformCredential cred = buildCredential(null, null, null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.clientIdMasked()).isEqualTo("****");
        }

        @Test
        @DisplayName("should set hasClientSecret based on both clientId and clientSecret present")
        void from_hasClientSecret() {
            PlatformCredential cred = buildCredential("12345678", "secret", null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasClientSecret()).isTrue();
        }

        @Test
        @DisplayName("should set hasApiKey flag correctly")
        void from_hasApiKey() {
            PlatformCredential cred = buildCredential(null, null, "my-api-key", null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasApiKey()).isTrue();
            assertThat(response.hasClientSecret()).isFalse();
        }

        @Test
        @DisplayName("should set hasBasicAuth flag correctly")
        void from_hasBasicAuth() {
            PlatformCredential cred = buildCredential(null, null, null, "user", "pass");
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasBasicAuth()).isTrue();
        }

        @Test
        @DisplayName("should set hasCustomFields correctly for non-empty map")
        void from_hasCustomFields() {
            PlatformCredential cred = new PlatformCredential(
                    1L, "test", "Test", AuthType.CUSTOM,
                    null, null, null, null, null,
                    null, null, null, null, null, null, true,
                    Map.of("key", "value"),
                    java.math.BigDecimal.ZERO,
                    500,
                    Instant.now(), Instant.now(), null, null
            );
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasCustomFields()).isTrue();
        }

        @Test
        @DisplayName("should set hasCustomFields to false for empty map")
        void from_hasCustomFieldsFalse() {
            PlatformCredential cred = buildCredential(null, null, null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasCustomFields()).isFalse();
        }

        @Test
        @DisplayName("should set hasCustomFields to false for null map")
        void from_hasCustomFieldsFalseNull() {
            PlatformCredential cred = new PlatformCredential(
                    1L, "test", "Test", AuthType.OAUTH2,
                    null, null, null, null, null,
                    null, null, null, null, null, null, true,
                    null,
                    java.math.BigDecimal.ZERO,
                    500,
                    Instant.now(), Instant.now(), null, null
            );
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.hasCustomFields()).isFalse();
        }

        @Test
        @DisplayName("should copy non-sensitive fields correctly")
        void from_copiesFields() {
            Instant now = Instant.now();
            PlatformCredential cred = new PlatformCredential(
                    99L, "gmail", "Gmail", AuthType.OAUTH2,
                    "client-id-12345678", "secret", null, null, null,
                    "https://auth.url", "https://token.url", "email profile",
                    "gmail-icon", "email", "Gmail integration", false, true,
                    Map.of(),
                    java.math.BigDecimal.ZERO,
                    500,
                    now, now, "admin", null, "primary"
            );

            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.id()).isEqualTo(99L);
            assertThat(response.integrationName()).isEqualTo("gmail");
            assertThat(response.displayName()).isEqualTo("Gmail");
            assertThat(response.authType()).isEqualTo("oauth2");
            assertThat(response.authUrl()).isEqualTo("https://auth.url");
            assertThat(response.tokenUrl()).isEqualTo("https://token.url");
            assertThat(response.defaultScopes()).isEqualTo("email profile");
            assertThat(response.iconSlug()).isEqualTo("gmail-icon");
            assertThat(response.category()).isEqualTo("email");
            assertThat(response.description()).isEqualTo("Gmail integration");
            assertThat(response.showUnverifiedAppWarning()).isFalse();
            assertThat(response.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should include endpoint list when provided")
        void from_withEndpoints() {
            PlatformCredential cred = buildCredential("12345678", "secret", null, null, null);
            List<EndpointStatusResponse> endpoints = List.of(
                    new EndpointStatusResponse("tool1", "Tool 1", "GET", "/api/test", true)
            );

            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred, endpoints);

            assertThat(response.endpoints()).hasSize(1);
            assertThat(response.endpoints().get(0).toolId()).isEqualTo("tool1");
        }

        @Test
        @DisplayName("should default to empty endpoints list")
        void from_defaultEmptyEndpoints() {
            PlatformCredential cred = buildCredential(null, null, null, null, null);
            PlatformCredentialResponse response = PlatformCredentialResponse.from(cred);

            assertThat(response.endpoints()).isEmpty();
        }
    }

    // ========== OAuth2Credentials Tests ==========

    @Nested
    @DisplayName("OAuth2Credentials")
    class OAuth2CredentialsTests {

        @Test
        @DisplayName("isValid should return true when both fields are non-blank")
        void isValid_true() {
            OAuth2Credentials creds = new OAuth2Credentials("client-id", "client-secret");
            assertThat(creds.isValid()).isTrue();
        }

        @Test
        @DisplayName("isValid should return false when clientId is null")
        void isValid_falseNullClientId() {
            OAuth2Credentials creds = new OAuth2Credentials(null, "client-secret");
            assertThat(creds.isValid()).isFalse();
        }

        @Test
        @DisplayName("isValid should return false when clientSecret is blank")
        void isValid_falseBlankSecret() {
            OAuth2Credentials creds = new OAuth2Credentials("client-id", "  ");
            assertThat(creds.isValid()).isFalse();
        }
    }

    // ========== CreatePlatformCredentialRequest Tests ==========

    @Nested
    @DisplayName("CreatePlatformCredentialRequest")
    class CreatePlatformCredentialRequestTests {

        @Test
        @DisplayName("authTypeEnum should delegate to AuthType.fromValue")
        void authTypeEnum_delegatesToFromValue() {
            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "test", "Test", "apikey",
                    null, null, "key", null, null,
                    null, null, null, null, null, null, null, null, null
            );
            assertThat(request.authTypeEnum()).isEqualTo(AuthType.API_KEY);
        }

        @Test
        @DisplayName("authTypeEnum should return OAUTH2 for null authType")
        void authTypeEnum_nullDefault() {
            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "test", "Test", null,
                    null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null
            );
            assertThat(request.authTypeEnum()).isEqualTo(AuthType.OAUTH2);
        }

        @Test
        @DisplayName("legacy constructors leave showUnverifiedAppWarning unset so the service can apply its default")
        void legacyConstructors_defaultWarningFlagIsNull() {
            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "test", "Test", "oauth2",
                    null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null
            );

            assertThat(request.showUnverifiedAppWarning()).isNull();
        }
    }

    // ========== Helpers ==========

    private PlatformCredential buildCredential(
            String clientId, String clientSecret, String apiKey,
            String username, String password
    ) {
        return new PlatformCredential(
                1L, "test-integration", "Test Integration", AuthType.OAUTH2,
                clientId, clientSecret, apiKey, username, password,
                "https://auth.url", "https://token.url", "scope1 scope2",
                "test-icon", "test-category", "Test description", true,
                Map.of(),
                java.math.BigDecimal.ZERO,
                500,
                Instant.now(), Instant.now(), "admin", null
        );
    }
}
