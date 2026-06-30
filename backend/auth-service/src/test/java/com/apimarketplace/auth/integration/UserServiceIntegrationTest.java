package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.dto.UserProfile;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for UserService with full Spring context.
 * Tests service methods with real repository wiring against H2 database.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@DisplayName("UserService Integration Tests")
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOnboardingRepository onboardingRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("svcuser", "svc@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setFirstName("Service");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of("USER"));
        testUser.setUserVersion(1L);
        testUser = userRepository.saveAndFlush(testUser);
    }

    @Nested
    @DisplayName("Find Operations")
    class FindOperations {

        @Test
        @DisplayName("Should find user by username through service")
        void shouldFindByUsername() {
            Optional<User> found = userService.findByUsername("svcuser");

            assertThat(found).isPresent();
            assertThat(found.get().getEmail()).isEqualTo("svc@example.com");
        }

        @Test
        @DisplayName("Should find user by ID through service")
        void shouldFindById() {
            Optional<User> found = userService.findById(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo("svcuser");
        }

        @Test
        @DisplayName("Should return empty for non-existent username")
        void shouldReturnEmptyForNonExistent() {
            Optional<User> found = userService.findByUsername("nonexistent");

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("Save Operations")
    class SaveOperations {

        @Test
        @DisplayName("Should save new user through service")
        void shouldSaveNewUser() {
            User newUser = new User("newuser", "new@example.com", AuthProvider.GOOGLE, "google|new1");
            newUser.setEnabled(true);
            newUser.setUserVersion(1L);

            User saved = userService.save(newUser);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getUsername()).isEqualTo("newuser");

            // Verify it's actually in the database
            Optional<User> found = userRepository.findByUsername("newuser");
            assertThat(found).isPresent();
        }
    }

    @Nested
    @DisplayName("Update Profile Operations")
    class UpdateProfileOperations {

        @Test
        @DisplayName("Should update basic info")
        void shouldUpdateBasicInfo() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("UpdatedFirst");
            request.setLastName("UpdatedLast");
            request.setAvatarUrl("https://example.com/avatar.png");

            User updated = userService.updateProfile(testUser, request);

            assertThat(updated.getFirstName()).isEqualTo("UpdatedFirst");
            assertThat(updated.getLastName()).isEqualTo("UpdatedLast");
            assertThat(updated.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        }

        @Test
        @DisplayName("Should update username when valid")
        void shouldUpdateValidUsername() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("validnewname");

            User updated = userService.updateProfile(testUser, request);

            assertThat(updated.getUsername()).isEqualTo("validnewname");
        }

        @Test
        @DisplayName("Should not update username when same as current")
        void shouldNotUpdateSameUsername() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("svcuser");

            User updated = userService.updateProfile(testUser, request);

            assertThat(updated.getUsername()).isEqualTo("svcuser");
        }

        @Test
        @DisplayName("Should reject duplicate username")
        void shouldRejectDuplicateUsername() {
            // Create another user
            User otherUser = new User("otheruser", "other@example.com", AuthProvider.KEYCLOAK, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
            otherUser.setEnabled(true);
            otherUser.setUserVersion(1L);
            userRepository.saveAndFlush(otherUser);

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("otheruser");

            assertThatThrownBy(() -> userService.updateProfile(testUser, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should update OIDC data fields")
        void shouldUpdateOidcData() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setEmail("newemail@example.com");
            request.setPicture("https://example.com/pic.jpg");
            request.setGivenName("Given");
            request.setFamilyName("Family");
            request.setEmailVerified(true);

            User updated = userService.updateProfile(testUser, request);

            assertThat(updated.getEmail()).isEqualTo("newemail@example.com");
            assertThat(updated.getAvatarUrl()).isEqualTo("https://example.com/pic.jpg");
            assertThat(updated.getFirstName()).isEqualTo("Given");
            assertThat(updated.getLastName()).isEqualTo("Family");
            assertThat(updated.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("Should only update provided fields (null fields untouched)")
        void shouldOnlyUpdateProvidedFields() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("OnlyFirst");
            // lastName, avatarUrl, etc. are null - should not change

            User updated = userService.updateProfile(testUser, request);

            assertThat(updated.getFirstName()).isEqualTo("OnlyFirst");
            assertThat(updated.getLastName()).isEqualTo("User"); // Original value
            assertThat(updated.getEmail()).isEqualTo("svc@example.com"); // Original value
        }
    }

    @Nested
    @DisplayName("Deactivate User")
    class DeactivateUser {

        @Test
        @DisplayName("Should deactivate user")
        void shouldDeactivateUser() {
            User deactivated = userService.deactivateUser(testUser);

            assertThat(deactivated.isEnabled()).isFalse();

            // Verify in database
            Optional<User> found = userRepository.findById(testUser.getId());
            assertThat(found).isPresent();
            assertThat(found.get().isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Map to UserProfile")
    class MapToUserProfile {

        @Test
        @DisplayName("Should map user to UserProfile DTO correctly")
        void shouldMapToUserProfile() {
            UserProfile profile = userService.mapToUserProfile(testUser);

            assertThat(profile.getId()).isEqualTo(testUser.getId());
            assertThat(profile.getUsername()).isEqualTo("svcuser");
            assertThat(profile.getEmail()).isEqualTo("svc@example.com");
            assertThat(profile.getFirstName()).isEqualTo("Service");
            assertThat(profile.getLastName()).isEqualTo("User");
            assertThat(profile.getAuthProvider()).isEqualTo("keycloak");
            assertThat(profile.isEmailVerified()).isTrue();
            assertThat(profile.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should include displayName from onboarding")
        void shouldIncludeDisplayNameFromOnboarding() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "ServiceDisplay");
            onboardingRepository.saveAndFlush(onboarding);

            UserProfile profile = userService.mapToUserProfile(testUser);

            assertThat(profile.getDisplayName()).isEqualTo("ServiceDisplay");
        }

        @Test
        @DisplayName("Should have null displayName when no onboarding exists")
        void shouldHaveNullDisplayNameWithoutOnboarding() {
            UserProfile profile = userService.mapToUserProfile(testUser);

            assertThat(profile.getDisplayName()).isNull();
        }
    }
}
