package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.OnboardingRequest;
import com.apimarketplace.auth.dto.OnboardingResponse;
import com.apimarketplace.auth.service.OnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingController Tests")
class OnboardingControllerTest {

    @Mock
    private OnboardingService onboardingService;

    @InjectMocks
    private OnboardingController controller;

    private static final String PROVIDER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @Nested
    @DisplayName("GET /api/onboarding/status")
    class GetStatusTests {

        @Test
        @DisplayName("should return onboarding status")
        void shouldReturnOnboardingStatus() {
            OnboardingResponse expectedResponse = OnboardingResponse.needsOnboarding();
            when(onboardingService.getOnboardingStatus(PROVIDER_ID)).thenReturn(expectedResponse);

            ResponseEntity<OnboardingResponse> response = controller.getStatus(PROVIDER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(expectedResponse);
        }
    }

    @Nested
    @DisplayName("GET /api/onboarding/needs")
    class NeedsOnboardingTests {

        @Test
        @DisplayName("should return true when onboarding is needed")
        void shouldReturnTrueWhenOnboardingNeeded() {
            when(onboardingService.needsOnboarding(PROVIDER_ID)).thenReturn(true);

            ResponseEntity<Map<String, Boolean>> response = controller.needsOnboarding(PROVIDER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("needsOnboarding", true);
        }

        @Test
        @DisplayName("should return false when onboarding is not needed")
        void shouldReturnFalseWhenOnboardingNotNeeded() {
            when(onboardingService.needsOnboarding(PROVIDER_ID)).thenReturn(false);

            ResponseEntity<Map<String, Boolean>> response = controller.needsOnboarding(PROVIDER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("needsOnboarding", false);
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/save")
    class SaveOnboardingTests {

        @Test
        @DisplayName("should save onboarding and return response")
        void shouldSaveOnboarding() {
            OnboardingRequest request = new OnboardingRequest();
            OnboardingResponse expectedResponse = OnboardingResponse.needsOnboarding();
            when(onboardingService.saveOnboarding(eq(PROVIDER_ID), any(OnboardingRequest.class)))
                    .thenReturn(expectedResponse);

            ResponseEntity<OnboardingResponse> response = controller.saveOnboarding(PROVIDER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(expectedResponse);
        }

        @Test
        @DisplayName("should return BAD_REQUEST when save fails with IllegalArgumentException")
        void shouldReturnBadRequestWhenSaveFails() {
            OnboardingRequest request = new OnboardingRequest();
            when(onboardingService.saveOnboarding(eq(PROVIDER_ID), any(OnboardingRequest.class)))
                    .thenThrow(new IllegalArgumentException("Invalid data"));

            ResponseEntity<OnboardingResponse> response = controller.saveOnboarding(PROVIDER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/complete")
    class CompleteOnboardingTests {

        @Test
        @DisplayName("should complete onboarding and return response")
        void shouldCompleteOnboarding() {
            OnboardingRequest request = new OnboardingRequest();
            OnboardingResponse expectedResponse = OnboardingResponse.needsOnboarding();
            when(onboardingService.completeOnboarding(eq(PROVIDER_ID), any(OnboardingRequest.class)))
                    .thenReturn(expectedResponse);

            ResponseEntity<OnboardingResponse> response = controller.completeOnboarding(PROVIDER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return BAD_REQUEST when complete fails with IllegalArgumentException")
        void shouldReturnBadRequestWhenCompleteFails() {
            OnboardingRequest request = new OnboardingRequest();
            when(onboardingService.completeOnboarding(eq(PROVIDER_ID), any(OnboardingRequest.class)))
                    .thenThrow(new IllegalArgumentException("Missing required fields"));

            ResponseEntity<OnboardingResponse> response = controller.completeOnboarding(PROVIDER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("POST /api/onboarding/skip")
    class SkipOnboardingTests {

        @Test
        @DisplayName("should skip onboarding with display name")
        void shouldSkipOnboarding() {
            OnboardingResponse expectedResponse = OnboardingResponse.needsOnboarding();
            when(onboardingService.skipOnboarding(PROVIDER_ID, "MyDisplay"))
                    .thenReturn(expectedResponse);

            ResponseEntity<OnboardingResponse> response = controller.skipOnboarding(
                    PROVIDER_ID, new OnboardingRequest("MyDisplay"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return BAD_REQUEST when skip throws IllegalArgumentException")
        void shouldReturnBadRequestWhenSkipFails() {
            when(onboardingService.skipOnboarding(PROVIDER_ID, "TakenName"))
                    .thenThrow(new IllegalArgumentException("Display name taken"));

            ResponseEntity<OnboardingResponse> response = controller.skipOnboarding(
                    PROVIDER_ID, new OnboardingRequest("TakenName"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /api/onboarding/check-display-name")
    class CheckDisplayNameTests {

        @Test
        @DisplayName("should return available when display name is free")
        void shouldReturnAvailableWhenDisplayNameFree() {
            when(onboardingService.isDisplayNameAvailable("NewName", PROVIDER_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.checkDisplayName(PROVIDER_ID, "NewName");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("available", true);
            assertThat(response.getBody()).containsEntry("displayName", "NewName");
        }

        @Test
        @DisplayName("should return not available when display name is taken")
        void shouldReturnNotAvailableWhenDisplayNameTaken() {
            when(onboardingService.isDisplayNameAvailable("TakenName", PROVIDER_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.checkDisplayName(PROVIDER_ID, "TakenName");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("available", false);
        }
    }

    @Nested
    @DisplayName("Missing provider identity")
    class MissingProviderIdentityTests {

        @Test
        @DisplayName("should reject status without provider identity")
        void shouldRejectStatusWithoutProviderIdentity() {
            ResponseEntity<OnboardingResponse> response = controller.getStatus(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should reject needs without provider identity")
        void shouldRejectNeedsWithoutProviderIdentity() {
            ResponseEntity<Map<String, Boolean>> response = controller.needsOnboarding(" ");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should reject save without provider identity")
        void shouldRejectSaveWithoutProviderIdentity() {
            ResponseEntity<OnboardingResponse> response = controller.saveOnboarding(null, new OnboardingRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should reject complete without provider identity")
        void shouldRejectCompleteWithoutProviderIdentity() {
            ResponseEntity<OnboardingResponse> response = controller.completeOnboarding(null, new OnboardingRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should reject skip without provider identity")
        void shouldRejectSkipWithoutProviderIdentity() {
            ResponseEntity<OnboardingResponse> response = controller.skipOnboarding(null, new OnboardingRequest("Display"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should reject display name checks without provider identity")
        void shouldRejectDisplayNameChecksWithoutProviderIdentity() {
            ResponseEntity<Map<String, Object>> response = controller.checkDisplayName(null, "Display");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
