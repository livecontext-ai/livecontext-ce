package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.service.UserResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionController Integration Tests")
class UserResolutionControllerIntegrationTest {

    private MockMvc mockMvc;

    @Mock
    private UserResolutionService userResolutionService;

    @InjectMocks
    private UserResolutionController userResolutionController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userResolutionController).build();
    }

    // ===== GET /api/users/resolve =====

    @Nested
    @DisplayName("GET /api/users/resolve")
    class ResolveUser {

        @Test
        @DisplayName("should resolve user with valid providerId and JWT")
        void shouldResolveUserWithValidProviderIdAndJwt() throws Exception {
            UserResolutionResponse response = new UserResolutionResponse(
                    1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "test@email.com", "FREE",
                    Set.of("USER"), 1L,
                    true, false, false, false
            );

            when(userResolutionService.resolveUser("f47ac10b-58cc-4372-a567-0e02b2c3d479", "fake.jwt.token")).thenReturn(response);

            mockMvc.perform(get("/api/users/resolve")
                            .param("providerId", "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .header("Authorization", "Bearer fake.jwt.token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(1))
                    .andExpect(jsonPath("$.providerId").value("f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(jsonPath("$.email").value("test@email.com"))
                    .andExpect(jsonPath("$.plan").value("FREE"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("should return 400 when providerId is empty")
        void shouldReturn400WhenProviderIdEmpty() throws Exception {
            mockMvc.perform(get("/api/users/resolve")
                            .param("providerId", "")
                            .header("Authorization", "Bearer some.jwt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when no Authorization header")
        void shouldReturn400WhenNoAuthHeader() throws Exception {
            mockMvc.perform(get("/api/users/resolve")
                            .param("providerId", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when user not found")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(userResolutionService.resolveUser("00000000-0000-0000-0000-000000000000", "fake.jwt")).thenReturn(null);

            mockMvc.perform(get("/api/users/resolve")
                            .param("providerId", "00000000-0000-0000-0000-000000000000")
                            .header("Authorization", "Bearer fake.jwt"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 when service throws exception")
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(userResolutionService.resolveUser("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", "fake.jwt"))
                    .thenThrow(new RuntimeException("DB connection failed"));

            mockMvc.perform(get("/api/users/resolve")
                            .param("providerId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
                            .header("Authorization", "Bearer fake.jwt"))
                    .andExpect(status().isInternalServerError());
        }
    }

    // ===== GET /api/users/can-request =====

    @Nested
    @DisplayName("GET /api/users/can-request")
    class CanRequest {

        @Test
        @DisplayName("should return true when user can make request")
        void shouldReturnTrueWhenAllowed() throws Exception {
            when(userResolutionService.canUserMakeRequest("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(true);

            mockMvc.perform(get("/api/users/can-request")
                            .param("providerId", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("true"));
        }

        @Test
        @DisplayName("should return false when user cannot make request")
        void shouldReturnFalseWhenDenied() throws Exception {
            when(userResolutionService.canUserMakeRequest("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")).thenReturn(false);

            mockMvc.perform(get("/api/users/can-request")
                            .param("providerId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("false"));
        }

        @Test
        @DisplayName("should return 400 when providerId is empty")
        void shouldReturn400WhenProviderIdEmpty() throws Exception {
            mockMvc.perform(get("/api/users/can-request")
                            .param("providerId", ""))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===== POST /api/users/invalidate-cache =====

    @Nested
    @DisplayName("POST /api/users/invalidate-cache")
    class InvalidateCache {

        @Test
        @DisplayName("should return new version when cache invalidated")
        void shouldReturnNewVersion() throws Exception {
            when(userResolutionService.updateUserVersion("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(6L);

            mockMvc.perform(post("/api/users/invalidate-cache")
                            .param("providerId", "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("6"));
        }

        @Test
        @DisplayName("should return 404 when user not found for cache invalidation")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(userResolutionService.updateUserVersion("00000000-0000-0000-0000-000000000000")).thenReturn(null);

            mockMvc.perform(post("/api/users/invalidate-cache")
                            .param("providerId", "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when providerId is empty")
        void shouldReturn400WhenProviderIdEmpty() throws Exception {
            mockMvc.perform(post("/api/users/invalidate-cache")
                            .param("providerId", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 500 when service throws exception")
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(userResolutionService.updateUserVersion("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(post("/api/users/invalidate-cache")
                            .param("providerId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"))
                    .andExpect(status().isInternalServerError());
        }
    }

    // ===== GET /api/users/health =====

    @Nested
    @DisplayName("GET /api/users/health")
    class HealthCheck {

        @Test
        @DisplayName("should return health status")
        void shouldReturnHealthStatus() throws Exception {
            mockMvc.perform(get("/api/users/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("UserResolutionService is healthy"));
        }
    }
}
