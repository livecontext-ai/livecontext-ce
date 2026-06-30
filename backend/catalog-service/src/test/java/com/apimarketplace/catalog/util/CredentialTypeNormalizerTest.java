package com.apimarketplace.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the agent-facing credential-type vocabulary. The catalog column {@code apis.auth_type}
 * drifted over import history (both {@code apiKey} and {@code api_key} exist for the same
 * mechanism), so the agent must always be handed ONE stable token per mechanism - the same
 * set {@code HttpExecutionService} reasons about.
 */
@DisplayName("CredentialTypeNormalizer")
class CredentialTypeNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
        // the apiKey/api_key drift collapses to one token - the whole point of the normalizer
        "apiKey, api_key",
        "api_key, api_key",
        "API_KEY, api_key",
        "api-key, api_key",
        "oauth2, oauth2",
        "OAuth2, oauth2",
        "oauth, oauth2",
        "bearer_token, bearer_token",
        "bearer, bearer_token",
        "basic_auth, basic_auth",
        "basic, basic_auth",
        "none, none"
    })
    @DisplayName("maps every known raw value to its canonical token")
    void mapsKnownValues(String raw, String expected) {
        assertThat(CredentialTypeNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom", "CUSTOM", " custom "})
    @DisplayName("passes an unrecognized value through lower-cased and trimmed (covers 'custom')")
    void passesThroughUnknownLowercased(String raw) {
        assertThat(CredentialTypeNormalizer.normalize(raw)).isEqualTo("custom");
    }

    @Test
    @DisplayName("null / blank degrade to 'none' so the agent positively concludes no credential is needed")
    void nullOrBlankIsNone() {
        assertThat(CredentialTypeNormalizer.normalize(null)).isEqualTo("none");
        assertThat(CredentialTypeNormalizer.normalize("")).isEqualTo("none");
        assertThat(CredentialTypeNormalizer.normalize("   ")).isEqualTo("none");
    }

    @Nested
    @DisplayName("buildRequirement (shared response_schema / approval_needed shape)")
    class BuildRequirement {

        @Test
        @DisplayName("oauth2 + non-empty scopes emits both type and requiredScopes")
        void oauthWithScopes() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("authType", "oauth2");
            info.put("requiredScopes", List.of("gmail.send", "gmail.readonly"));

            Map<String, Object> req = CredentialTypeNormalizer.buildRequirement(info);

            assertThat(req).containsEntry("type", "oauth2");
            assertThat(req.get("requiredScopes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("gmail.send", "gmail.readonly");
        }

        @Test
        @DisplayName("empty requiredScopes list is omitted (no noise key)")
        void emptyScopesOmitted() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("authType", "oauth2");
            info.put("requiredScopes", List.of());

            Map<String, Object> req = CredentialTypeNormalizer.buildRequirement(info);

            assertThat(req).containsEntry("type", "oauth2");
            assertThat(req).doesNotContainKey("requiredScopes");
        }

        @Test
        @DisplayName("api_key info carries only the type (no scopes key)")
        void apiKeyNoScopes() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("authType", "apiKey");

            Map<String, Object> req = CredentialTypeNormalizer.buildRequirement(info);

            assertThat(req).containsEntry("type", "api_key");
            assertThat(req).doesNotContainKey("requiredScopes");
        }

        @Test
        @DisplayName("null or authType-less info degrades to type:none (never throws, never null)")
        void nullInfoIsNone() {
            assertThat(CredentialTypeNormalizer.buildRequirement(null))
                .containsEntry("type", "none");
            assertThat(CredentialTypeNormalizer.buildRequirement(new LinkedHashMap<>()))
                .containsEntry("type", "none");
        }
    }
}
