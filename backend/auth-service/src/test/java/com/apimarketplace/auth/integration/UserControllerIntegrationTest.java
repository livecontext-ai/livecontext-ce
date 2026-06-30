package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for UserController with full Spring context.
 * Uses MockMvc to test HTTP request/response with security headers.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("UserController Integration Tests")
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOnboardingRepository onboardingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private static final String GATEWAY_SECRET_HEADER = "X-Gateway-Secret";
    private static final String GATEWAY_TIMESTAMP_HEADER = "X-Gateway-Timestamp";
    private static final String PROVIDER_ID_HEADER = "X-Provider-ID";
    private static final String USER_ID_HEADER = "X-User-ID";

    @BeforeEach
    void setUp() {
        testUser = new User("integuser", "integ@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setFirstName("Integration");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of("USER"));
        testUser.setUserVersion(1L);
        testUser = userRepository.saveAndFlush(testUser);
    }

    @Nested
    @DisplayName("GET /api/users/profile")
    class GetProfile {

        @Test
        @DisplayName("Should return user profile when valid headers provided")
        void shouldReturnProfileWithValidHeaders() throws Exception {
            // Create onboarding for the user to get displayName
            UserOnboarding onboarding = new UserOnboarding(testUser, "IntegrationDisplay");
            onboarding.markCompleted();
            onboardingRepository.saveAndFlush(onboarding);

            mockMvc.perform(get("/api/users/profile")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testUser.getId()))
                    .andExpect(jsonPath("$.username").value("integuser"))
                    .andExpect(jsonPath("$.email").value("integ@example.com"))
                    .andExpect(jsonPath("$.firstName").value("Integration"))
                    .andExpect(jsonPath("$.lastName").value("User"))
                    .andExpect(jsonPath("$.displayName").value("IntegrationDisplay"));
        }

        @Test
        @DisplayName("Should return 404 for non-existent user ID")
        void shouldReturn404ForNonExistentUserId() throws Exception {
            mockMvc.perform(get("/api/users/profile")
                            .header(USER_ID_HEADER, "99999")
                            .header(PROVIDER_ID_HEADER, "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 for invalid user ID format")
        void shouldReturn400ForInvalidUserIdFormat() throws Exception {
            mockMvc.perform(get("/api/users/profile")
                            .header(USER_ID_HEADER, "not-a-number")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /api/users/profile")
    class UpdateProfile {

        @Test
        @DisplayName("Should update user profile successfully")
        void shouldUpdateProfileSuccessfully() throws Exception {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("UpdatedFirst");
            request.setLastName("UpdatedLast");

            mockMvc.perform(put("/api/users/profile")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value("UpdatedFirst"))
                    .andExpect(jsonPath("$.lastName").value("UpdatedLast"));
        }

        @Test
        @DisplayName("Should update username when valid")
        void shouldUpdateUsername() throws Exception {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("newusername");

            mockMvc.perform(put("/api/users/profile")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newusername"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/users/profile")
    class DeactivateProfile {

        @Test
        @DisplayName("Should deactivate the gateway user when headers are provided")
        void shouldDeactivateGatewayUser() throws Exception {
            mockMvc.perform(delete("/api/users/profile")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk());

            User deactivated = userRepository.findById(testUser.getId()).orElseThrow();
            assertFalse(deactivated.isEnabled());
        }

        @Test
        @DisplayName("Should return bad request for invalid gateway user id")
        void shouldReturnBadRequestForInvalidGatewayUserId() throws Exception {
            mockMvc.perform(delete("/api/users/profile")
                            .header(USER_ID_HEADER, "not-a-number")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return not found for missing gateway user")
        void shouldReturnNotFoundForMissingGatewayUser() throws Exception {
            mockMvc.perform(delete("/api/users/profile")
                            .header(USER_ID_HEADER, "99999")
                            .header(PROVIDER_ID_HEADER, "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/users/status")
    class GetUserStatus {

        @Test
        @DisplayName("Should return user status with onboarding info")
        void shouldReturnUserStatusWithOnboardingInfo() throws Exception {
            // Create completed onboarding
            UserOnboarding onboarding = new UserOnboarding(testUser, "StatusUser");
            onboarding.markCompleted();
            onboardingRepository.saveAndFlush(onboarding);

            mockMvc.perform(get("/api/users/status")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(testUser.getId()))
                    .andExpect(jsonPath("$.needsOnboarding").value(false))
                    .andExpect(jsonPath("$.email").value("integ@example.com"))
                    .andExpect(jsonPath("$.roles").isArray());
        }

        @Test
        @DisplayName("Should indicate onboarding needed when not completed")
        void shouldIndicateOnboardingNeeded() throws Exception {
            mockMvc.perform(get("/api/users/status")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(true));
        }

        @Test
        @DisplayName("Should return 404 for non-existent user")
        void shouldReturn404ForNonExistentUser() throws Exception {
            mockMvc.perform(get("/api/users/status")
                            .header(USER_ID_HEADER, "99999")
                            .header(PROVIDER_ID_HEADER, "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/users/check-username")
    class CheckUsername {

        @Test
        @DisplayName("Should return available for unused username")
        void shouldReturnAvailableForUnusedUsername() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "availableuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.username").value("availableuser"));
        }

        @Test
        @DisplayName("Should return not available for taken username")
        void shouldReturnNotAvailableForTakenUsername() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "integuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
        }

        @Test
        @DisplayName("Should return available when same user owns the username")
        void shouldReturnAvailableForOwnUsername() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "integuser")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true));
        }

        @Test
        @DisplayName("Should return error for empty username")
        void shouldReturnErrorForEmptyUsername() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return error for too short username")
        void shouldReturnErrorForTooShortUsername() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "ab"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return error for invalid characters in username")
        void shouldReturnErrorForInvalidCharacters() throws Exception {
            mockMvc.perform(get("/api/users/check-username")
                            .param("username", "user@name!"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/users/reserved-usernames")
    class ReservedUsernames {

        @Test
        @DisplayName("Should return list of reserved usernames")
        void shouldReturnReservedUsernames() throws Exception {
            mockMvc.perform(get("/api/users/reserved-usernames"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reservedUsernames").isArray())
                    .andExpect(jsonPath("$.count").isNumber());
        }
    }

    @Nested
    @DisplayName("GET /api/users/health")
    class HealthCheck {

        @Test
        @DisplayName("Should return health status without authentication")
        void shouldReturnHealthStatus() throws Exception {
            mockMvc.perform(get("/api/users/health"))
                    .andExpect(status().isOk());
        }
    }
}
