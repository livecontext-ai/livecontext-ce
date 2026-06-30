package com.apimarketplace.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserProfile Tests")
class UserProfileTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create with null fields")
        void shouldCreateWithNullFields() {
            UserProfile profile = new UserProfile();

            assertThat(profile.getId()).isNull();
            assertThat(profile.getUsername()).isNull();
            assertThat(profile.getDisplayName()).isNull();
            assertThat(profile.getEmail()).isNull();
            assertThat(profile.getFirstName()).isNull();
            assertThat(profile.getLastName()).isNull();
            assertThat(profile.getAvatarUrl()).isNull();
            assertThat(profile.getAuthProvider()).isNull();
            assertThat(profile.isEmailVerified()).isFalse();
            assertThat(profile.isEnabled()).isFalse();
            assertThat(profile.getCreatedAt()).isNull();
            assertThat(profile.getLastLoginAt()).isNull();
            assertThat(profile.getAge()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create with all parameters")
        void shouldCreateWithAllParams() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime login = LocalDateTime.now().minusHours(1);
            LocalDateTime age = LocalDateTime.of(1990, 5, 20, 0, 0);

            UserProfile profile = new UserProfile(1L, "testuser", "Test Display",
                    "test@example.com", "John", "Doe",
                    "https://avatar.url", "KEYCLOAK", true, true,
                    now, login, age, null);

            assertThat(profile.getId()).isEqualTo(1L);
            assertThat(profile.getUsername()).isEqualTo("testuser");
            assertThat(profile.getDisplayName()).isEqualTo("Test Display");
            assertThat(profile.getEmail()).isEqualTo("test@example.com");
            assertThat(profile.getFirstName()).isEqualTo("John");
            assertThat(profile.getLastName()).isEqualTo("Doe");
            assertThat(profile.getAvatarUrl()).isEqualTo("https://avatar.url");
            assertThat(profile.getAuthProvider()).isEqualTo("KEYCLOAK");
            assertThat(profile.isEmailVerified()).isTrue();
            assertThat(profile.isEnabled()).isTrue();
            assertThat(profile.getCreatedAt()).isEqualTo(now);
            assertThat(profile.getLastLoginAt()).isEqualTo(login);
            assertThat(profile.getAge()).isEqualTo(age);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get all fields")
        void shouldSetAndGetAllFields() {
            UserProfile profile = new UserProfile();
            LocalDateTime now = LocalDateTime.now();

            profile.setId(42L);
            profile.setUsername("user42");
            profile.setDisplayName("Display42");
            profile.setEmail("user42@test.com");
            profile.setFirstName("Jane");
            profile.setLastName("Smith");
            profile.setAvatarUrl("https://example.com/avatar");
            profile.setAuthProvider("GOOGLE");
            profile.setEmailVerified(true);
            profile.setEnabled(true);
            profile.setCreatedAt(now);
            profile.setLastLoginAt(now);
            profile.setAge(now);

            assertThat(profile.getId()).isEqualTo(42L);
            assertThat(profile.getUsername()).isEqualTo("user42");
            assertThat(profile.getDisplayName()).isEqualTo("Display42");
            assertThat(profile.getEmail()).isEqualTo("user42@test.com");
            assertThat(profile.getFirstName()).isEqualTo("Jane");
            assertThat(profile.getLastName()).isEqualTo("Smith");
            assertThat(profile.getAvatarUrl()).isEqualTo("https://example.com/avatar");
            assertThat(profile.getAuthProvider()).isEqualTo("GOOGLE");
            assertThat(profile.isEmailVerified()).isTrue();
            assertThat(profile.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include key fields in toString")
        void shouldIncludeKeyFieldsInToString() {
            UserProfile profile = new UserProfile();
            profile.setId(1L);
            profile.setUsername("testuser");
            profile.setEmail("test@example.com");

            String result = profile.toString();

            assertThat(result).contains("id=1");
            assertThat(result).contains("username='testuser'");
            assertThat(result).contains("email='test@example.com'");
        }
    }
}
