package com.apimarketplace.auth.service.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuthProfileNormalizer Tests")
class OAuthProfileNormalizerTest {

    private OAuthProfileNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new OAuthProfileNormalizer();
    }

    @Nested
    @DisplayName("normalizeGoogle() method")
    class NormalizeGoogleTests {

        @Test
        @DisplayName("should normalize complete Google profile")
        void shouldNormalizeCompleteGoogleProfile() {
            Map<String, Object> attrs = createGoogleAttributes(
                    "123456789",
                    "user@example.com",
                    true,
                    "John",
                    "Doe",
                    "John Doe",
                    "https://example.com/avatar.jpg"
            );

            OAuthProfile result = normalizer.normalizeGoogle(attrs);

            assertThat(result.provider()).isEqualTo("google");
            assertThat(result.providerId()).isEqualTo("123456789");
            assertThat(result.email()).isEqualTo("user@example.com");
            assertThat(result.emailVerified()).isTrue();
            assertThat(result.firstName()).isEqualTo("John");
            assertThat(result.lastName()).isEqualTo("Doe");
            assertThat(result.displayName()).isEqualTo("John Doe");
            assertThat(result.avatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        }

        @Test
        @DisplayName("should handle minimal Google profile")
        void shouldHandleMinimalGoogleProfile() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", "minimal-sub-123");

            OAuthProfile result = normalizer.normalizeGoogle(attrs);

            assertThat(result.provider()).isEqualTo("google");
            assertThat(result.providerId()).isEqualTo("minimal-sub-123");
            assertThat(result.email()).isNull();
            assertThat(result.emailVerified()).isFalse();
            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
        }

        @Test
        @DisplayName("should handle email_verified as string 'true'")
        void shouldHandleEmailVerifiedAsString() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", "123");
            attrs.put("email_verified", "true");

            OAuthProfile result = normalizer.normalizeGoogle(attrs);

            assertThat(result.emailVerified()).isTrue();
        }

        @Test
        @DisplayName("should handle email_verified as string 'false'")
        void shouldHandleEmailVerifiedAsFalseString() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", "123");
            attrs.put("email_verified", "false");

            OAuthProfile result = normalizer.normalizeGoogle(attrs);

            assertThat(result.emailVerified()).isFalse();
        }

        @Test
        @DisplayName("should handle null values gracefully")
        void shouldHandleNullValuesGracefully() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", null);
            attrs.put("email", null);
            attrs.put("name", null);

            OAuthProfile result = normalizer.normalizeGoogle(attrs);

            assertThat(result.providerId()).isNull();
            assertThat(result.email()).isNull();
            assertThat(result.displayName()).isNull();
        }
    }

    @Nested
    @DisplayName("normalizeGithub() method")
    class NormalizeGithubTests {

        @Test
        @DisplayName("should normalize complete GitHub profile with public email")
        void shouldNormalizeCompleteGithubProfileWithPublicEmail() {
            Map<String, Object> attrs = createGithubAttributes(
                    12345678,
                    "octocat",
                    "The Octocat",
                    "octocat@github.com",
                    "https://avatars.githubusercontent.com/u/583231"
            );

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.provider()).isEqualTo("github");
            assertThat(result.providerId()).isEqualTo("12345678");
            assertThat(result.email()).isEqualTo("octocat@github.com");
            assertThat(result.emailVerified()).isTrue();
            assertThat(result.displayName()).isEqualTo("The Octocat");
            assertThat(result.avatarUrl()).isEqualTo("https://avatars.githubusercontent.com/u/583231");
        }

        @Test
        @DisplayName("should extract first and last name from display name")
        void shouldExtractFirstAndLastNameFromDisplayName() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", 123);
            attrs.put("login", "johndoe");
            attrs.put("name", "John Michael Doe");

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.firstName()).isEqualTo("John Michael");
            assertThat(result.lastName()).isEqualTo("Doe");
        }

        @Test
        @DisplayName("should handle single name without space")
        void shouldHandleSingleNameWithoutSpace() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", 123);
            attrs.put("login", "madonna");
            attrs.put("name", "Madonna");

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
            assertThat(result.displayName()).isEqualTo("Madonna");
        }

        @Test
        @DisplayName("should use login as display name when name is null")
        void shouldUseLoginAsDisplayNameWhenNameIsNull() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", 123);
            attrs.put("login", "coderguy");
            attrs.put("name", null);

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.displayName()).isEqualTo("coderguy");
        }

        @Test
        @DisplayName("should handle null email with emailVerified false")
        void shouldHandleNullEmailWithEmailVerifiedFalse() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", 123);
            attrs.put("login", "privateemail");
            attrs.put("email", null);

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.email()).isNull();
            assertThat(result.emailVerified()).isFalse();
        }

        @Test
        @DisplayName("should handle integer ID conversion")
        void shouldHandleIntegerIdConversion() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("id", 987654321);
            attrs.put("login", "user");

            OAuthProfile result = normalizer.normalizeGithub(attrs, null);

            assertThat(result.providerId()).isEqualTo("987654321");
        }
    }

    @Nested
    @DisplayName("OAuthProfile record")
    class OAuthProfileTests {

        @Test
        @DisplayName("should create profile with builder")
        void shouldCreateProfileWithBuilder() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("test")
                    .providerId("123")
                    .email("test@example.com")
                    .emailVerified(true)
                    .firstName("Test")
                    .lastName("User")
                    .displayName("Test User")
                    .avatarUrl("https://example.com/avatar.jpg")
                    .build();

            assertThat(profile.provider()).isEqualTo("test");
            assertThat(profile.providerId()).isEqualTo("123");
            assertThat(profile.email()).isEqualTo("test@example.com");
            assertThat(profile.emailVerified()).isTrue();
            assertThat(profile.firstName()).isEqualTo("Test");
            assertThat(profile.lastName()).isEqualTo("User");
            assertThat(profile.displayName()).isEqualTo("Test User");
            assertThat(profile.avatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        }

        @Test
        @DisplayName("should create profile with minimal data using builder")
        void shouldCreateProfileWithMinimalDataUsingBuilder() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("minimal")
                    .providerId("456")
                    .build();

            assertThat(profile.provider()).isEqualTo("minimal");
            assertThat(profile.providerId()).isEqualTo("456");
            assertThat(profile.email()).isNull();
            assertThat(profile.emailVerified()).isFalse();
        }
    }

    // Helper methods

    private Map<String, Object> createGoogleAttributes(
            String sub, String email, boolean emailVerified,
            String givenName, String familyName, String name, String picture) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", sub);
        attrs.put("email", email);
        attrs.put("email_verified", emailVerified);
        attrs.put("given_name", givenName);
        attrs.put("family_name", familyName);
        attrs.put("name", name);
        attrs.put("picture", picture);
        return attrs;
    }

    private Map<String, Object> createGithubAttributes(
            int id, String login, String name, String email, String avatarUrl) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", id);
        attrs.put("login", login);
        attrs.put("name", name);
        attrs.put("email", email);
        attrs.put("avatar_url", avatarUrl);
        return attrs;
    }
}
