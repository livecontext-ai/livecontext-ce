package com.apimarketplace.trigger.client.webhook;

import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebhookConfig} record in trigger-client.
 */
@DisplayName("WebhookConfig")
class WebhookConfigTest {

    @Nested
    @DisplayName("defaults")
    class DefaultsTests {

        @Test
        @DisplayName("Should create default config with POST and no auth")
        void shouldCreateDefaultConfig() {
            WebhookConfig config = WebhookConfig.defaults();

            assertThat(config.webhookToken()).isNull();
            assertThat(config.httpMethod()).isEqualTo("POST");
            assertThat(config.authType()).isEqualTo("none");
            assertThat(config.basicUsername()).isNull();
            assertThat(config.basicPassword()).isNull();
            assertThat(config.authHeaderName()).isNull();
            assertThat(config.authHeaderValue()).isNull();
            assertThat(config.jwtSecretKey()).isNull();
            assertThat(config.jwtAlgorithm()).isEqualTo("HS256");
        }

        @Test
        @DisplayName("defaults() should not require authentication")
        void defaultsShouldNotRequireAuth() {
            assertThat(WebhookConfig.defaults().requiresAuth()).isFalse();
        }
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateTokenTests {

        @Test
        @DisplayName("Should generate token with wh_ prefix")
        void shouldGenerateTokenWithPrefix() {
            String token = WebhookConfig.generateToken();
            assertThat(token).startsWith("wh_");
        }

        @Test
        @DisplayName("Should generate unique tokens")
        void shouldGenerateUniqueTokens() {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                tokens.add(WebhookConfig.generateToken());
            }
            assertThat(tokens).hasSize(50);
        }

        @Test
        @DisplayName("Should not contain dashes in token")
        void shouldNotContainDashes() {
            String token = WebhookConfig.generateToken();
            String uuidPart = token.substring(3);
            assertThat(uuidPart).doesNotContain("-");
        }
    }

    @Nested
    @DisplayName("fromTriggerParams")
    class FromTriggerParamsTests {

        @Test
        @DisplayName("Should return defaults when params is null")
        void shouldReturnDefaultsWhenNull() {
            WebhookConfig config = WebhookConfig.fromTriggerParams(null);
            assertThat(config.httpMethod()).isEqualTo("POST");
            assertThat(config.authType()).isEqualTo("none");
        }

        @Test
        @DisplayName("Should return defaults when params has no webhook key")
        void shouldReturnDefaultsWhenNoWebhookKey() {
            Map<String, Object> params = Map.of("other", "data");
            WebhookConfig config = WebhookConfig.fromTriggerParams(params);
            assertThat(config.httpMethod()).isEqualTo("POST");
            assertThat(config.authType()).isEqualTo("none");
        }

        @Test
        @DisplayName("Should return defaults when webhook value is not a Map")
        void shouldReturnDefaultsWhenWebhookNotMap() {
            Map<String, Object> params = Map.of("webhook", "not-a-map");
            WebhookConfig config = WebhookConfig.fromTriggerParams(params);
            assertThat(config.httpMethod()).isEqualTo("POST");
        }

        @Test
        @DisplayName("Should extract all fields from nested webhook map")
        void shouldExtractAllFieldsFromNestedMap() {
            Map<String, Object> webhookMap = new LinkedHashMap<>();
            webhookMap.put("webhookToken", "wh_abc123");
            webhookMap.put("httpMethod", "GET");
            webhookMap.put("authType", "basic");
            webhookMap.put("basicUsername", "admin");
            webhookMap.put("basicPassword", "secret");
            webhookMap.put("authHeaderName", "X-API-Key");
            webhookMap.put("authHeaderValue", "key-123");
            webhookMap.put("jwtSecretKey", "my-secret");
            webhookMap.put("jwtAlgorithm", "HS512");

            Map<String, Object> params = Map.of("webhook", webhookMap);
            WebhookConfig config = WebhookConfig.fromTriggerParams(params);

            assertThat(config.webhookToken()).isEqualTo("wh_abc123");
            assertThat(config.httpMethod()).isEqualTo("GET");
            assertThat(config.authType()).isEqualTo("basic");
            assertThat(config.basicUsername()).isEqualTo("admin");
            assertThat(config.basicPassword()).isEqualTo("secret");
            assertThat(config.authHeaderName()).isEqualTo("X-API-Key");
            assertThat(config.authHeaderValue()).isEqualTo("key-123");
            assertThat(config.jwtSecretKey()).isEqualTo("my-secret");
            assertThat(config.jwtAlgorithm()).isEqualTo("HS512");
        }

        @Test
        @DisplayName("Should use default httpMethod POST when not specified in nested map")
        void shouldDefaultHttpMethodInNestedMap() {
            Map<String, Object> webhookMap = new LinkedHashMap<>();
            webhookMap.put("authType", "header");

            Map<String, Object> params = Map.of("webhook", webhookMap);
            WebhookConfig config = WebhookConfig.fromTriggerParams(params);

            assertThat(config.httpMethod()).isEqualTo("POST");
        }

        @Test
        @DisplayName("Should convert non-String values via toString()")
        void shouldConvertNonStringValues() {
            Map<String, Object> webhookMap = new LinkedHashMap<>();
            webhookMap.put("webhookToken", 12345);
            webhookMap.put("httpMethod", "POST");

            Map<String, Object> params = Map.of("webhook", webhookMap);
            WebhookConfig config = WebhookConfig.fromTriggerParams(params);

            assertThat(config.webhookToken()).isEqualTo("12345");
        }
    }

    @Nested
    @DisplayName("requiresAuth")
    class RequiresAuthTests {

        @ParameterizedTest
        @ValueSource(strings = {"basic", "header", "jwt", "BASIC", "Header", "JWT"})
        @DisplayName("Should return true for auth types other than none")
        void shouldReturnTrueForAuthTypes(String authType) {
            WebhookConfig config = new WebhookConfig(null, "POST", authType, null, null, null, null, null, null);
            assertThat(config.requiresAuth()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"none", "NONE", "None"})
        @DisplayName("Should return false for none auth type (case-insensitive)")
        void shouldReturnFalseForNone(String authType) {
            WebhookConfig config = new WebhookConfig(null, "POST", authType, null, null, null, null, null, null);
            assertThat(config.requiresAuth()).isFalse();
        }

        @Test
        @DisplayName("Should return false for null auth type")
        void shouldReturnFalseForNull() {
            WebhookConfig config = new WebhookConfig(null, "POST", null, null, null, null, null, null, null);
            assertThat(config.requiresAuth()).isFalse();
        }
    }

    @Nested
    @DisplayName("matchesMethod")
    class MatchesMethodTests {

        @ParameterizedTest
        @CsvSource({
            "POST, POST, true",
            "POST, post, true",
            "post, POST, true",
            "GET, GET, true",
            "GET, POST, false",
            "PUT, DELETE, false"
        })
        @DisplayName("Should match methods case-insensitively")
        void shouldMatchMethodsCaseInsensitive(String configMethod, String requestMethod, boolean expected) {
            WebhookConfig config = new WebhookConfig(null, configMethod, "none", null, null, null, null, null, null);
            assertThat(config.matchesMethod(requestMethod)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should default to POST when httpMethod is null (strict security)")
        void shouldDefaultToPostWhenNull() {
            WebhookConfig config = new WebhookConfig(null, null, "none", null, null, null, null, null, null);
            assertThat(config.matchesMethod("POST")).isTrue();
            assertThat(config.matchesMethod("GET")).isFalse();
        }
    }

    @Nested
    @DisplayName("hasValidToken")
    class HasValidTokenTests {

        @Test
        @DisplayName("Should return true for non-blank token")
        void shouldReturnTrueForValidToken() {
            WebhookConfig config = new WebhookConfig("wh_abc", "POST", "none", null, null, null, null, null, null);
            assertThat(config.hasValidToken()).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("Should return false for null, empty, or blank token")
        void shouldReturnFalseForInvalidToken(String token) {
            WebhookConfig config = new WebhookConfig(token, "POST", "none", null, null, null, null, null, null);
            assertThat(config.hasValidToken()).isFalse();
        }
    }

    @Nested
    @DisplayName("toMap")
    class ToMapTests {

        @Test
        @DisplayName("Should include all non-null fields in map")
        void shouldIncludeAllNonNullFields() {
            WebhookConfig config = new WebhookConfig(
                    "wh_tok", "POST", "basic", "user", "pass",
                    "X-Key", "val", "secret", "HS256");
            Map<String, Object> map = config.toMap();

            assertThat(map)
                    .containsEntry("webhookToken", "wh_tok")
                    .containsEntry("httpMethod", "POST")
                    .containsEntry("authType", "basic")
                    .containsEntry("basicUsername", "user")
                    .containsEntry("basicPassword", "pass")
                    .containsEntry("authHeaderName", "X-Key")
                    .containsEntry("authHeaderValue", "val")
                    .containsEntry("jwtSecretKey", "secret")
                    .containsEntry("jwtAlgorithm", "HS256");
        }

        @Test
        @DisplayName("Should omit null fields from map")
        void shouldOmitNullFields() {
            WebhookConfig config = new WebhookConfig(null, "POST", "none", null, null, null, null, null, null);
            Map<String, Object> map = config.toMap();

            assertThat(map).containsKey("httpMethod");
            assertThat(map).containsKey("authType");
            assertThat(map).doesNotContainKey("webhookToken");
            assertThat(map).doesNotContainKey("basicUsername");
        }
    }

    @Nested
    @DisplayName("fromStandaloneWebhook")
    class FromStandaloneWebhookTests {

        @Test
        @DisplayName("Should create config from DTO with decrypted auth config")
        void shouldCreateFromDtoWithAuth() {
            StandaloneWebhookDto dto = new StandaloneWebhookDto();
            dto.setToken("wh_tok");
            dto.setHttpMethod("POST");
            dto.setAuthType("basic");

            Map<String, String> decryptedAuth = Map.of(
                    "basicUsername", "user",
                    "basicPassword", "pass");

            WebhookConfig config = WebhookConfig.fromStandaloneWebhook(dto, decryptedAuth);

            assertThat(config.webhookToken()).isEqualTo("wh_tok");
            assertThat(config.httpMethod()).isEqualTo("POST");
            assertThat(config.authType()).isEqualTo("basic");
            assertThat(config.basicUsername()).isEqualTo("user");
            assertThat(config.basicPassword()).isEqualTo("pass");
        }

        @Test
        @DisplayName("Should default httpMethod to POST when DTO httpMethod is null")
        void shouldDefaultHttpMethod() {
            StandaloneWebhookDto dto = new StandaloneWebhookDto();
            dto.setToken("wh_tok");

            WebhookConfig config = WebhookConfig.fromStandaloneWebhook(dto, null);

            assertThat(config.httpMethod()).isEqualTo("POST");
            assertThat(config.authType()).isEqualTo("none");
        }

        @Test
        @DisplayName("Should handle null decrypted auth config gracefully")
        void shouldHandleNullDecryptedConfig() {
            StandaloneWebhookDto dto = new StandaloneWebhookDto();
            dto.setToken("wh_tok");
            dto.setAuthType("basic");

            WebhookConfig config = WebhookConfig.fromStandaloneWebhook(dto, null);

            assertThat(config.basicUsername()).isNull();
            assertThat(config.basicPassword()).isNull();
        }
    }
}
