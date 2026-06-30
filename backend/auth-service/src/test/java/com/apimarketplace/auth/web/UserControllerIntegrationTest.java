package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserProfile;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.util.ReservedUsernames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController Integration Tests")
class UserControllerIntegrationTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

    @Mock
    private OnboardingService onboardingService;

    @InjectMocks
    private UserController userController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();
    }

    // ===== Helper methods =====

    private User createTestUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setRoles(Set.of("USER"));
        user.setCreatedAt(LocalDateTime.now());
        user.setUserVersion(1L);
        return user;
    }

    private UserProfile createTestProfile(Long id, String username) {
        return new UserProfile(
                id,
                username,
                "Test Display",
                username + "@test.com",
                "Test",
                "User",
                "https://avatar.com/img.jpg",
                "keycloak",
                true,
                true,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }

    // ===== GET /api/users/profile =====

    @Nested
    @DisplayName("GET /api/users/profile")
    class GetProfile {

        @Test
        @DisplayName("should return user profile when X-User-ID and X-Provider-ID headers are present")
        void shouldReturnProfileWithHeaders() throws Exception {
            User user = createTestUser(1L, "testuser");
            UserProfile profile = createTestProfile(1L, "testuser");

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(userService.mapToUserProfile(user)).thenReturn(profile);

            mockMvc.perform(get("/api/users/profile")
                            .header("X-User-ID", "1")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("testuser@test.com"))
                    .andExpect(jsonPath("$.firstName").value("Test"))
                    .andExpect(jsonPath("$.lastName").value("User"))
                    .andExpect(jsonPath("$.authProvider").value("keycloak"))
                    .andExpect(jsonPath("$.emailVerified").value(true))
                    .andExpect(jsonPath("$.enabled").value(true));

            verify(userService).findById(1L);
            verify(userService).mapToUserProfile(user);
        }

        @Test
        @DisplayName("should return 404 when user not found with X-User-ID header")
        void shouldReturn404WhenUserNotFoundWithHeaders() throws Exception {
            when(userService.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/profile")
                            .header("X-User-ID", "999")
                            .header("X-Provider-ID", "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());

            verify(userService).findById(999L);
        }

        @Test
        @DisplayName("should return 400 when X-User-ID is not a valid number")
        void shouldReturn400WhenUserIdNotNumeric() throws Exception {
            mockMvc.perform(get("/api/users/profile")
                            .header("X-User-ID", "invalid")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).findById(any());
        }

        @Test
        @DisplayName("should return 404 when no headers and no authentication")
        void shouldReturn404WhenNoHeadersAndNoAuth() throws Exception {
            // Without headers, falls back to SecurityContextHolder which is empty
            mockMvc.perform(get("/api/users/profile"))
                    .andExpect(status().isNotFound());
        }
    }

    // ===== PUT /api/users/profile =====

    @Nested
    @DisplayName("PUT /api/users/profile")
    class UpdateProfile {

        @Test
        @DisplayName("should update profile successfully with gateway headers")
        void shouldUpdateProfileWithHeaders() throws Exception {
            User user = createTestUser(1L, "testuser");
            UserProfile updatedProfile = createTestProfile(1L, "testuser");
            updatedProfile.setFirstName("Updated");
            updatedProfile.setLastName("Name");

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("Updated");
            request.setLastName("Name");

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(userService.updateProfile(eq(user), any(UserProfileUpdateRequest.class))).thenReturn(user);
            when(userService.mapToUserProfile(user)).thenReturn(updatedProfile);

            mockMvc.perform(put("/api/users/profile")
                            .header("X-User-ID", "1")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value("Updated"))
                    .andExpect(jsonPath("$.lastName").value("Name"));

            verify(userService).findById(1L);
            verify(userService).updateProfile(eq(user), any(UserProfileUpdateRequest.class));
        }

        @Test
        @DisplayName("should return 404 when user not found for update")
        void shouldReturn404WhenUserNotFoundForUpdate() throws Exception {
            when(userService.findById(999L)).thenReturn(Optional.empty());

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("Update");

            mockMvc.perform(put("/api/users/profile")
                            .header("X-User-ID", "999")
                            .header("X-Provider-ID", "00000000-0000-0000-0000-000000000000")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when X-User-ID is not numeric for update")
        void shouldReturn400WhenUserIdNotNumericForUpdate() throws Exception {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("Update");

            mockMvc.perform(put("/api/users/profile")
                            .header("X-User-ID", "abc")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===== DELETE /api/users/profile =====

    @Nested
    @DisplayName("DELETE /api/users/profile")
    class DeactivateProfile {

        @Test
        @DisplayName("should deactivate profile when gateway headers are present")
        void shouldDeactivateProfileWithHeaders() throws Exception {
            User user = createTestUser(1L, "testuser");
            when(userService.findById(1L)).thenReturn(Optional.of(user));

            mockMvc.perform(delete("/api/users/profile")
                            .header("X-User-ID", "1")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk());

            verify(userService).findById(1L);
            verify(userService).deactivateUser(user);
        }

        @Test
        @DisplayName("should return 404 when gateway user is missing for deactivation")
        void shouldReturn404WhenGatewayUserMissingForDeactivation() throws Exception {
            when(userService.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/users/profile")
                            .header("X-User-ID", "999")
                            .header("X-Provider-ID", "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());

            verify(userService).findById(999L);
            verify(userService, never()).deactivateUser(any());
        }

        @Test
        @DisplayName("should return 400 when gateway user id is invalid for deactivation")
        void shouldReturn400WhenGatewayUserIdInvalidForDeactivation() throws Exception {
            mockMvc.perform(delete("/api/users/profile")
                            .header("X-User-ID", "not-a-number")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).findById(any());
            verify(userService, never()).deactivateUser(any());
        }

        @Test
        @DisplayName("should return 404 when no authenticated user for deactivation")
        void shouldReturn404WhenNoAuthForDeactivation() throws Exception {
            // No SecurityContext user, no headers
            mockMvc.perform(delete("/api/users/profile"))
                    .andExpect(status().isNotFound());
        }
    }

    // ===== GET /api/users/status =====

    @Nested
    @DisplayName("GET /api/users/status")
    class GetUserStatus {

        @Test
        @DisplayName("should return status with gateway headers")
        void shouldReturnStatusWithHeaders() throws Exception {
            User user = createTestUser(1L, "testuser");
            user.setRoles(Set.of("USER", "ADMIN"));

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);

            mockMvc.perform(get("/api/users/status")
                            .header("X-User-ID", "1")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(1))
                    .andExpect(jsonPath("$.needsOnboarding").value(false))
                    .andExpect(jsonPath("$.firstLogin").value(false))
                    .andExpect(jsonPath("$.isAdmin").value(true))
                    .andExpect(jsonPath("$.email").value("testuser@test.com"));

            verify(onboardingService).needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("should indicate onboarding needed for new user")
        void shouldIndicateOnboardingNeeded() throws Exception {
            User user = createTestUser(2L, "newuser");

            when(userService.findById(2L)).thenReturn(Optional.of(user));
            when(onboardingService.needsOnboarding("a1b2c3d4-e5f6-7890-abcd-ef1234567890")).thenReturn(true);

            mockMvc.perform(get("/api/users/status")
                            .header("X-User-ID", "2")
                            .header("X-Provider-ID", "a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(true))
                    .andExpect(jsonPath("$.firstLogin").value(true))
                    .andExpect(jsonPath("$.profileIncomplete").value(true));
        }

        @Test
        @DisplayName("should return 404 when user not found for status")
        void shouldReturn404WhenUserNotFoundForStatus() throws Exception {
            when(userService.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/status")
                            .header("X-User-ID", "999")
                            .header("X-Provider-ID", "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when X-User-ID is invalid for status")
        void shouldReturn400WhenUserIdInvalidForStatus() throws Exception {
            mockMvc.perform(get("/api/users/status")
                            .header("X-User-ID", "notanumber")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return isAdmin false when user has only USER role")
        void shouldReturnIsAdminFalseForNonAdmin() throws Exception {
            User user = createTestUser(1L, "regularuser");
            user.setRoles(Set.of("USER"));

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);

            mockMvc.perform(get("/api/users/status")
                            .header("X-User-ID", "1")
                            .header("X-Provider-ID", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isAdmin").value(false));
        }
    }

    // ===== GET /api/users/check-username =====

    @Nested
    @DisplayName("GET /api/users/check-username")
    class CheckUsername {

        @Test
        @DisplayName("should return available true when username is free")
        void shouldReturnAvailableWhenFree() throws Exception {
            when(userService.findByUsername("freeuser")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "freeuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("freeuser"))
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.message").value("Username is available"));
        }

        @Test
        @DisplayName("should return available false when username is taken by another user")
        void shouldReturnUnavailableWhenTaken() throws Exception {
            User existingUser = createTestUser(2L, "takenuser");
            when(userService.findByUsername("takenuser")).thenReturn(Optional.of(existingUser));

            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "takenuser")
                            .header("X-User-ID", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.message").value("This username is already taken"));
        }

        @Test
        @DisplayName("should return available true when username belongs to the same user")
        void shouldReturnAvailableWhenSameUser() throws Exception {
            User sameUser = createTestUser(1L, "myusername");
            when(userService.findByUsername("myusername")).thenReturn(Optional.of(sameUser));

            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "myusername")
                            .header("X-User-ID", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true));
        }

        @Test
        @DisplayName("should return 400 when username is empty")
        void shouldReturn400WhenUsernameEmpty() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Username cannot be empty"));
        }

        @Test
        @DisplayName("should return 400 when username is too short")
        void shouldReturn400WhenUsernameTooShort() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "ab"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Username must be between 3 and 20 characters"));
        }

        @Test
        @DisplayName("should return 400 when username is too long")
        void shouldReturn400WhenUsernameTooLong() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "a".repeat(21)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Username must be between 3 and 20 characters"));
        }

        @Test
        @DisplayName("should return 400 when username has invalid characters")
        void shouldReturn400WhenUsernameHasInvalidChars() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "user@name"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Username can only contain letters, numbers, hyphens, and underscores"));
        }

        @Test
        @DisplayName("should return 400 when username is reserved")
        void shouldReturn400WhenUsernameReserved() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "admin"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("This username is reserved and cannot be used"));
        }

        @Test
        @DisplayName("should return available false when taken and no X-User-ID header")
        void shouldReturnUnavailableWhenTakenNoHeader() throws Exception {
            User existingUser = createTestUser(5L, "someone");
            when(userService.findByUsername("someone")).thenReturn(Optional.of(existingUser));

            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "someone"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
        }
    }

    // ===== GET /api/users/reserved-usernames =====

    @Nested
    @DisplayName("GET /api/users/reserved-usernames")
    class GetReservedUsernames {

        @Test
        @DisplayName("should return reserved usernames list")
        void shouldReturnReservedUsernamesList() throws Exception {
            mockMvc.perform(get("/api/users/reserved-usernames"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reservedUsernames").isArray())
                    .andExpect(jsonPath("$.count").isNumber())
                    .andExpect(jsonPath("$.message").value("List of reserved usernames"));
        }

        @Test
        @DisplayName("should return correct count of reserved usernames")
        void shouldReturnCorrectCount() throws Exception {
            int expectedCount = ReservedUsernames.getReservedUsernamesCount();

            mockMvc.perform(get("/api/users/reserved-usernames"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(expectedCount));
        }
    }
}
