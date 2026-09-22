package com.apimarketplace.catalog.service.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Reading an integration's scope policy out of its catalog credential template.
 *
 * <p>The shapes here are the ones actually in the column: a direct {@code oauth2Config}
 * object (what the seed importer and the signed bundle write) and the legacy
 * {@code metadata.value = "<json>"} envelope the catalog wire path still produces.
 * Anything it cannot read must answer "unknown", never "restricted".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IntegrationScopePolicyReader")
class IntegrationScopePolicyReaderTest {

    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";

    @Mock private JdbcTemplate jdbcTemplate;

    private IntegrationScopePolicyReader reader(String authMode) {
        return new IntegrationScopePolicyReader(jdbcTemplate, authMode);
    }

    /**
     * Stubs the lookup FOR ONE INTEGRATION, matching the bound parameter rather than any
     * string: a stub that answers whatever it is asked cannot show that the reader asked
     * about the integration it was given.
     */
    private void stub(String integration, String metadata) {
        when(jdbcTemplate.queryForList(contains("catalog.credentials"), eq(integration)))
                .thenReturn(List.of(Map.of("metadata", metadata)));
    }

    @Nested
    @DisplayName("the shapes the column holds")
    class Shapes {

        @Test
        @DisplayName("reads the direct oauth2Config object")
        void directObject() {
            stub("gmail", "{\"oauth2Config\":{\"scopes\":[\"" + SEND + "\"],"
                    + "\"byokOnlyScopes\":[\"" + READONLY + "\"]}}");
            IntegrationScopePolicy policy = reader("").forIntegration("gmail");
            assertThat(policy.declared()).isTrue();
            assertThat(policy.platformScopes()).containsExactly(SEND);
            assertThat(policy.byokOnlyScopes()).containsExactly(READONLY);
        }

        @Test
        @DisplayName("unwraps the legacy jsonb envelope that carries the config as a string")
        void legacyValueEnvelope() {
            stub("gmail", "{\"value\":\"{\\\"oauth2Config\\\":{\\\"scopes\\\":[\\\"" + SEND + "\\\"]}}\"}");
            assertThat(reader("").forIntegration("gmail").platformScopes()).containsExactly(SEND);
        }

        @Test
        @DisplayName("skips a variant row that declares nothing and takes the one that does")
        void picksTheRowThatDeclares() {
            when(jdbcTemplate.queryForList(contains("catalog.credentials"), eq("gmail"))).thenReturn(List.of(
                    Map.of("metadata", "{\"authType\":\"oauth2\"}"),
                    Map.of("metadata", "{\"oauth2Config\":{\"scopes\":[\"" + SEND + "\"]}}")));
            assertThat(reader("").forIntegration("gmail").platformScopes()).containsExactly(SEND);
        }
    }

    @Nested
    @DisplayName("everything it cannot read answers unknown, never restricted")
    class FailsOpen {

        @Test
        @DisplayName("no row at all")
        void noRow() {
            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
            assertThat(reader("").forIntegration("gmail").declared()).isFalse();
        }

        @Test
        @DisplayName("no oauth2Config in the metadata")
        void noOauthConfig() {
            stub("stripe", "{\"authType\":\"apiKey\"}");
            assertThat(reader("").forIntegration("stripe").declared()).isFalse();
        }

        @Test
        @DisplayName("an oauth2Config that declares no scope on either side")
        void emptyConfigIsNotAPolicy() {
            // Treating this as a policy would make EVERY required scope look restricted,
            // and every caller would be told to register an OAuth application.
            stub("acme", "{\"oauth2Config\":{\"authorizationUrl\":\"https://example.test\"}}");
            assertThat(reader("").forIntegration("acme").declared()).isFalse();
        }

        @Test
        @DisplayName("unparseable metadata")
        void unparseable() {
            stub("acme", "not json at all");
            assertThat(reader("").forIntegration("acme").declared()).isFalse();
        }

        @Test
        @DisplayName("the database throwing")
        void databaseFailure() {
            when(jdbcTemplate.queryForList(anyString(), anyString()))
                    .thenThrow(new RuntimeException("connection refused"));
            assertThat(reader("").forIntegration("gmail").declared()).isFalse();
        }

        @Test
        @DisplayName("a blank integration is not even asked about")
        void blankIntegration() {
            assertThat(reader("").forIntegration(null).declared()).isFalse();
            assertThat(reader("").forIntegration("  ").declared()).isFalse();
        }
    }

    @Nested
    @DisplayName("which install has no platform-shared OAuth app")
    class Edition {

        @Test
        @DisplayName("auth.mode=embedded means the user's own client is the only client")
        void embedded() {
            assertThat(reader("embedded").ownClientIsTheOnlyClient()).isTrue();
            assertThat(reader("EMBEDDED").ownClientIsTheOnlyClient()).isTrue();
        }

        @Test
        @DisplayName("keycloak does NOT, which is what keeps self-hosted enterprise on the managed side")
        void keycloakIsNotEmbedded() {
            // The trap: "self-hosted" and "embedded" are not the same set. A self-hosted
            // ENTERPRISE install runs Keycloak and DOES have a platform-shared OAuth app,
            // so widening on "self-hosted" would tell its users a restricted scope is
            // available when the shared consent screen never requests it.
            assertThat(reader("keycloak").ownClientIsTheOnlyClient()).isFalse();
        }

        @Test
        @DisplayName("absent, as in every microservice deployment, reads as managed cloud")
        void absentIsManaged() {
            assertThat(reader("").ownClientIsTheOnlyClient()).isFalse();
            assertThat(reader(null).ownClientIsTheOnlyClient()).isFalse();
        }

        @Test
        @DisplayName("the edition reaches the policy it produces, not just the reader")
        void editionReachesThePolicy() {
            stub("gmail", "{\"oauth2Config\":{\"scopes\":[\"" + SEND + "\"],"
                    + "\"byokOnlyScopes\":[\"" + READONLY + "\"]}}");
            assertThat(reader("embedded").forIntegration("gmail")
                    .scopesNeedingOwnOAuthClient(List.of(READONLY))).isEmpty();
            assertThat(reader("keycloak").forIntegration("gmail")
                    .scopesNeedingOwnOAuthClient(List.of(READONLY))).containsExactly(READONLY);
        }
    }
}
