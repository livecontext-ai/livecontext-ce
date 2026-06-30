package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.dto.OnboardingRequest;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for OnboardingController with full Spring context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("OnboardingController Integration Tests")
class OnboardingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOnboardingRepository onboardingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private static final String PROVIDER_ID_HEADER = "X-Provider-ID";

    @BeforeEach
    void setUp() {
        testUser = new User("onboardctrl", "onboardctrl@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setFirstName("Onboard");
        testUser.setLastName("Controller");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of("USER"));
        testUser.setUserVersion(1L);
        testUser = userRepository.saveAndFlush(testUser);
    }

    @Nested
    @DisplayName("GET /api/onboarding/status")
    class GetOnboardingStatus {

        @Test
        @DisplayName("Should return needs onboarding when no onboarding exists")
        void shouldReturnNeedsOnboarding() throws Exception {
            mockMvc.perform(get("/api/onboarding/status")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(true))
                    .andExpect(jsonPath("$.completed").value(false))
                    .andExpect(jsonPath("$.skipped").value(false));
        }

        @Test
        @DisplayName("Should return completed status when onboarding is done")
        void shouldReturnCompletedStatus() throws Exception {
            UserOnboarding onboarding = new UserOnboarding(testUser, "CompletedUser");
            onboarding.markCompleted();
            onboardingRepository.saveAndFlush(onboarding);

            mockMvc.perform(get("/api/onboarding/status")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(false))
                    .andExpect(jsonPath("$.completed").value(true))
                    .andExpect(jsonPath("$.displayName").value("CompletedUser"));
        }

        @Test
        @DisplayName("Should return skipped status")
        void shouldReturnSkippedStatus() throws Exception {
            UserOnboarding onboarding = new UserOnboarding(testUser, "SkippedUser");
            onboarding.markSkipped();
            onboardingRepository.saveAndFlush(onboarding);

            mockMvc.perform(get("/api/onboarding/status")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(false))
                    .andExpect(jsonPath("$.skipped").value(true));
        }
    }

    @Nested
    @DisplayName("GET /api/onboarding/needs")
    class NeedsOnboarding {

        @Test
        @DisplayName("Should return true when onboarding is needed")
        void shouldReturnTrueWhenOnboardingNeeded() throws Exception {
            mockMvc.perform(get("/api/onboarding/needs")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(true));
        }

        @Test
        @DisplayName("Should return false when onboarding is completed")
        void shouldReturnFalseWhenOnboardingCompleted() throws Exception {
            UserOnboarding onboarding = new UserOnboarding(testUser, "DoneUser");
            onboarding.markCompleted();
            onboardingRepository.saveAndFlush(onboarding);

            mockMvc.perform(get("/api/onboarding/needs")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.needsOnboarding").value(false));
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/save")
    class SaveOnboarding {

        @Test
        @DisplayName("Should save onboarding data successfully")
        void shouldSaveOnboardingData() throws Exception {
            OnboardingRequest request = new OnboardingRequest("SavedDisplay");
            request.setProfession("Developer");
            request.setCompanySize("startup");
            request.setExperienceLevel("advanced");
            request.setInterests(List.of("ai", "automation"));
            request.setUseCases(List.of("workflow-automation"));
            request.setCurrentStep(2);

            mockMvc.perform(post("/api/onboarding/save")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("SavedDisplay"))
                    .andExpect(jsonPath("$.profession").value("Developer"))
                    .andExpect(jsonPath("$.companySize").value("startup"))
                    .andExpect(jsonPath("$.experienceLevel").value("advanced"))
                    .andExpect(jsonPath("$.currentStep").value(2));

            // Verify persisted in database
            var saved = onboardingRepository.findByUserId(testUser.getId());
            assertThat(saved).isPresent();
            assertThat(saved.get().getDisplayName()).isEqualTo("SavedDisplay");
        }

        @Test
        @DisplayName("Should reject duplicate display name")
        void shouldRejectDuplicateDisplayName() throws Exception {
            // Create another user with a display name
            User otherUser = new User("other", "other@example.com", AuthProvider.KEYCLOAK, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
            otherUser.setEnabled(true);
            otherUser.setUserVersion(1L);
            otherUser = userRepository.saveAndFlush(otherUser);

            UserOnboarding existingOnboarding = new UserOnboarding(otherUser, "TakenName");
            onboardingRepository.saveAndFlush(existingOnboarding);

            OnboardingRequest request = new OnboardingRequest("TakenName");

            mockMvc.perform(post("/api/onboarding/save")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/complete")
    class CompleteOnboarding {

        @Test
        @DisplayName("Should complete onboarding successfully")
        void shouldCompleteOnboarding() throws Exception {
            OnboardingRequest request = new OnboardingRequest("CompleteDisplay");
            request.setProfession("Designer");
            request.setCompanySize("medium");
            request.setExperienceLevel("intermediate");
            request.setCurrentStep(3);

            mockMvc.perform(post("/api/onboarding/complete")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.completed").value(true))
                    .andExpect(jsonPath("$.needsOnboarding").value(false));

            // Verify in database
            var completed = onboardingRepository.findByUserId(testUser.getId());
            assertThat(completed).isPresent();
            assertThat(completed.get().isOnboardingCompleted()).isTrue();
            assertThat(completed.get().getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/skip")
    class SkipOnboarding {

        @Test
        @DisplayName("Should skip onboarding with display name")
        void shouldSkipOnboardingWithDisplayName() throws Exception {
            Map<String, String> body = Map.of("displayName", "SkippedDisplay");

            mockMvc.perform(post("/api/onboarding/skip")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.skipped").value(true));

            // Verify in database
            var skipped = onboardingRepository.findByUserId(testUser.getId());
            assertThat(skipped).isPresent();
            assertThat(skipped.get().isOnboardingSkipped()).isTrue();
            assertThat(skipped.get().getDisplayName()).isEqualTo("SkippedDisplay");
        }

        @Test
        @DisplayName("Should reject skip without display name")
        void shouldRejectSkipWithoutDisplayName() throws Exception {
            Map<String, String> body = Map.of("displayName", "");

            mockMvc.perform(post("/api/onboarding/skip")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject skip when display name is below the 3-char lower bound")
        void shouldRejectSkipWhenDisplayNameBelowLowerBound() throws Exception {
            Map<String, String> body = Map.of("displayName", "ab");

            mockMvc.perform(post("/api/onboarding/skip")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject skip when display name exceeds the 30-char upper bound")
        void shouldRejectSkipWhenDisplayNameExceedsUpperBound() throws Exception {
            Map<String, String> body = Map.of("displayName", "a".repeat(31));

            mockMvc.perform(post("/api/onboarding/skip")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/onboarding/check-display-name")
    class CheckDisplayName {

        @Test
        @DisplayName("Should return available for unused display name")
        void shouldReturnAvailableForUnusedName() throws Exception {
            mockMvc.perform(get("/api/onboarding/check-display-name")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .param("displayName", "UniqueNewName"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.displayName").value("UniqueNewName"));
        }

        @Test
        @DisplayName("Should return not available for taken display name")
        void shouldReturnNotAvailableForTakenName() throws Exception {
            // Create another user with a display name
            User otherUser = new User("displayother", "displayother@example.com", AuthProvider.KEYCLOAK, "b2c3d4e5-f6a7-8901-bcde-f12345678901");
            otherUser.setEnabled(true);
            otherUser.setUserVersion(1L);
            otherUser = userRepository.saveAndFlush(otherUser);

            UserOnboarding existing = new UserOnboarding(otherUser, "TakenDisplayName");
            onboardingRepository.saveAndFlush(existing);

            mockMvc.perform(get("/api/onboarding/check-display-name")
                            .header(PROVIDER_ID_HEADER, "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                            .param("displayName", "TakenDisplayName"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
        }
    }

    @Nested
    @DisplayName("Authentication boundary")
    class AuthenticationBoundary {

        @Test
        @DisplayName("Should reject status without provider identity instead of returning 500")
        void shouldRejectStatusWithoutProviderIdentity() throws Exception {
            mockMvc.perform(get("/api/onboarding/status"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should reject needs without provider identity instead of returning 500")
        void shouldRejectNeedsWithoutProviderIdentity() throws Exception {
            mockMvc.perform(get("/api/onboarding/needs"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should reject save without provider identity instead of returning 500")
        void shouldRejectSaveWithoutProviderIdentity() throws Exception {
            mockMvc.perform(post("/api/onboarding/save")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new OnboardingRequest("MissingProvider"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should reject display name checks without provider identity instead of returning 500")
        void shouldRejectDisplayNameChecksWithoutProviderIdentity() throws Exception {
            mockMvc.perform(get("/api/onboarding/check-display-name")
                            .param("displayName", "MissingProvider"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
