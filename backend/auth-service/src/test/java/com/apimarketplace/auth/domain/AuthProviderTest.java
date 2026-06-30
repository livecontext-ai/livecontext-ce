package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuthProvider Tests")
class AuthProviderTest {

    @Nested
    @DisplayName("getProvider()")
    class GetProviderTests {

        @Test
        @DisplayName("GOOGLE should return 'google'")
        void googleShouldReturnGoogle() {
            assertThat(AuthProvider.GOOGLE.getProvider()).isEqualTo("google");
        }

        @Test
        @DisplayName("GITHUB should return 'github'")
        void githubShouldReturnGithub() {
            assertThat(AuthProvider.GITHUB.getProvider()).isEqualTo("github");
        }

        @Test
        @DisplayName("LOCAL should return 'local'")
        void localShouldReturnLocal() {
            assertThat(AuthProvider.LOCAL.getProvider()).isEqualTo("local");
        }

        @Test
        @DisplayName("KEYCLOAK should return 'keycloak'")
        void keycloakShouldReturnKeycloak() {
            assertThat(AuthProvider.KEYCLOAK.getProvider()).isEqualTo("keycloak");
        }
    }

    @Nested
    @DisplayName("fromRegistrationId()")
    class FromRegistrationIdTests {

        @Test
        @DisplayName("should return GOOGLE for 'google'")
        void shouldReturnGoogleForGoogle() {
            assertThat(AuthProvider.fromRegistrationId("google")).isEqualTo(AuthProvider.GOOGLE);
        }

        @Test
        @DisplayName("should return GITHUB for 'github'")
        void shouldReturnGithubForGithub() {
            assertThat(AuthProvider.fromRegistrationId("github")).isEqualTo(AuthProvider.GITHUB);
        }

        @Test
        @DisplayName("should return KEYCLOAK for 'keycloak'")
        void shouldReturnKeycloakForKeycloak() {
            assertThat(AuthProvider.fromRegistrationId("keycloak")).isEqualTo(AuthProvider.KEYCLOAK);
        }

        @Test
        @DisplayName("should be case insensitive")
        void shouldBeCaseInsensitive() {
            assertThat(AuthProvider.fromRegistrationId("GOOGLE")).isEqualTo(AuthProvider.GOOGLE);
            assertThat(AuthProvider.fromRegistrationId("GitHub")).isEqualTo(AuthProvider.GITHUB);
            assertThat(AuthProvider.fromRegistrationId("KEYCLOAK")).isEqualTo(AuthProvider.KEYCLOAK);
        }

        @ParameterizedTest
        @ValueSource(strings = {"facebook", "twitter", "linkedin", "unknown", "auth0"})
        @DisplayName("should throw for unknown providers")
        void shouldThrowForUnknownProviders(String provider) {
            assertThatThrownBy(() -> AuthProvider.fromRegistrationId(provider))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown provider");
        }
    }

    @Nested
    @DisplayName("Enum values")
    class EnumValuesTests {

        @Test
        @DisplayName("should have exactly 4 values")
        void shouldHaveExactly4Values() {
            assertThat(AuthProvider.values()).hasSize(4);
        }

        @Test
        @DisplayName("should contain all expected values")
        void shouldContainAllExpectedValues() {
            assertThat(AuthProvider.values())
                    .containsExactly(AuthProvider.GOOGLE, AuthProvider.GITHUB, AuthProvider.LOCAL, AuthProvider.KEYCLOAK);
        }
    }
}
