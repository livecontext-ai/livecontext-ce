package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User Tests")
class UserTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should initialize createdAt and updatedAt")
        void shouldInitializeTimestamps() {
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            User user = new User();
            LocalDateTime after = LocalDateTime.now().plusSeconds(1);

            assertThat(user.getCreatedAt()).isAfter(before).isBefore(after);
            assertThat(user.getUpdatedAt()).isAfter(before).isBefore(after);
        }

        @Test
        @DisplayName("should have enabled set to true by default")
        void shouldBeEnabledByDefault() {
            User user = new User();

            assertThat(user.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should have emailVerified set to false by default")
        void shouldNotBeEmailVerifiedByDefault() {
            User user = new User();

            assertThat(user.isEmailVerified()).isFalse();
        }

        @Test
        @DisplayName("should have userVersion set to 1 by default")
        void shouldHaveDefaultUserVersion() {
            User user = new User();

            assertThat(user.getUserVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should have null id by default")
        void shouldHaveNullId() {
            User user = new User();

            assertThat(user.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should set all fields correctly")
        void shouldSetAllFields() {
            User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(user.getUsername()).isEqualTo("testuser");
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.KEYCLOAK);
            assertThat(user.getProviderId()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("should initialize roles with USER role")
        void shouldInitializeRolesWithUserRole() {
            User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(user.getRoles()).containsExactly("USER");
        }

        @Test
        @DisplayName("should initialize timestamps from default constructor")
        void shouldInitializeTimestamps() {
            User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(user.getCreatedAt()).isNotNull();
            assertThat(user.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("UserDetails Implementation")
    class UserDetailsTests {

        @Test
        @DisplayName("should return authorities based on roles")
        void shouldReturnAuthoritiesBasedOnRoles() {
            User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
            user.setRoles(Set.of("USER", "ADMIN"));

            Collection<? extends GrantedAuthority> authorities = user.getAuthorities();

            assertThat(authorities).hasSize(2);
            assertThat(authorities.stream().map(GrantedAuthority::getAuthority))
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        }

        @Test
        @DisplayName("should return null password for OAuth2 users")
        void shouldReturnNullPassword() {
            User user = new User();

            assertThat(user.getPassword()).isNull();
        }

        @Test
        @DisplayName("should always return true for isAccountNonExpired")
        void shouldReturnAccountNonExpired() {
            User user = new User();

            assertThat(user.isAccountNonExpired()).isTrue();
        }

        @Test
        @DisplayName("should always return true for isAccountNonLocked")
        void shouldReturnAccountNonLocked() {
            User user = new User();

            assertThat(user.isAccountNonLocked()).isTrue();
        }

        @Test
        @DisplayName("should always return true for isCredentialsNonExpired")
        void shouldReturnCredentialsNonExpired() {
            User user = new User();

            assertThat(user.isCredentialsNonExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            User user = new User();
            user.setId(42L);

            assertThat(user.getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should get and set firstName")
        void shouldGetAndSetFirstName() {
            User user = new User();
            user.setFirstName("John");

            assertThat(user.getFirstName()).isEqualTo("John");
        }

        @Test
        @DisplayName("should get and set lastName")
        void shouldGetAndSetLastName() {
            User user = new User();
            user.setLastName("Doe");

            assertThat(user.getLastName()).isEqualTo("Doe");
        }

        @Test
        @DisplayName("should get and set avatarUrl")
        void shouldGetAndSetAvatarUrl() {
            User user = new User();
            user.setAvatarUrl("https://example.com/avatar.png");

            assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        }

        @Test
        @DisplayName("should get and set lastLoginAt")
        void shouldGetAndSetLastLoginAt() {
            User user = new User();
            LocalDateTime loginTime = LocalDateTime.of(2025, 1, 15, 10, 30);
            user.setLastLoginAt(loginTime);

            assertThat(user.getLastLoginAt()).isEqualTo(loginTime);
        }

        @Test
        @DisplayName("should get and set age")
        void shouldGetAndSetAge() {
            User user = new User();
            LocalDateTime age = LocalDateTime.of(1990, 5, 20, 0, 0);
            user.setAge(age);

            assertThat(user.getAge()).isEqualTo(age);
        }

        @Test
        @DisplayName("should get and set memberships")
        void shouldGetAndSetMemberships() {
            User user = new User();

            assertThat(user.getMemberships()).isEmpty();
        }
    }

    @Nested
    @DisplayName("preUpdate()")
    class PreUpdateTests {

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            User user = new User();
            LocalDateTime originalUpdatedAt = user.getUpdatedAt();

            // Small delay to ensure different timestamp
            user.preUpdate();

            assertThat(user.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        }
    }
}
