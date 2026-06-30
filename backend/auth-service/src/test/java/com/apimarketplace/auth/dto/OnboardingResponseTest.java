package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OnboardingResponse Tests")
class OnboardingResponseTest {

    @Nested
    @DisplayName("needsOnboarding() factory method")
    class NeedsOnboardingFactoryTests {

        @Test
        @DisplayName("should create response indicating onboarding is needed")
        void shouldCreateNeedsOnboardingResponse() {
            OnboardingResponse response = OnboardingResponse.needsOnboarding();

            assertThat(response.isNeedsOnboarding()).isTrue();
            assertThat(response.isCompleted()).isFalse();
            assertThat(response.isSkipped()).isFalse();
            assertThat(response.getCurrentStep()).isZero();
        }
    }

    @Nested
    @DisplayName("fromEntity() factory method")
    class FromEntityFactoryTests {

        @Test
        @DisplayName("should map all fields from entity")
        void shouldMapAllFieldsFromEntity() {
            User user = new User();
            user.setId(1L);

            UserOnboarding onboarding = new UserOnboarding(user, "TestDisplay");
            onboarding.setProfession("Developer");
            onboarding.setCompanySize("startup");
            onboarding.setInterests(Arrays.asList("ai", "automation"));
            onboarding.setUseCases(Arrays.asList("chatbots", "api-testing"));
            onboarding.setExperienceLevel("intermediate");
            onboarding.setOnboardingStep(3);

            OnboardingResponse response = OnboardingResponse.fromEntity(onboarding);

            assertThat(response.isNeedsOnboarding()).isTrue();
            assertThat(response.isCompleted()).isFalse();
            assertThat(response.isSkipped()).isFalse();
            assertThat(response.getCurrentStep()).isEqualTo(3);
            assertThat(response.getDisplayName()).isEqualTo("TestDisplay");
            assertThat(response.getProfession()).isEqualTo("Developer");
            assertThat(response.getCompanySize()).isEqualTo("startup");
            assertThat(response.getInterests()).containsExactly("ai", "automation");
            assertThat(response.getUseCases()).containsExactly("chatbots", "api-testing");
            assertThat(response.getExperienceLevel()).isEqualTo("intermediate");
        }

        @Test
        @DisplayName("should reflect completed state")
        void shouldReflectCompletedState() {
            User user = new User();
            user.setId(1L);

            UserOnboarding onboarding = new UserOnboarding(user, "TestDisplay");
            onboarding.markCompleted();

            OnboardingResponse response = OnboardingResponse.fromEntity(onboarding);

            assertThat(response.isCompleted()).isTrue();
            assertThat(response.isNeedsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("should reflect skipped state")
        void shouldReflectSkippedState() {
            User user = new User();
            user.setId(1L);

            UserOnboarding onboarding = new UserOnboarding(user, "TestDisplay");
            onboarding.markSkipped();

            OnboardingResponse response = OnboardingResponse.fromEntity(onboarding);

            assertThat(response.isSkipped()).isTrue();
            assertThat(response.isNeedsOnboarding()).isFalse();
        }
    }

    @Nested
    @DisplayName("completed() factory method")
    class CompletedFactoryTests {

        @Test
        @DisplayName("should create completed response")
        void shouldCreateCompletedResponse() {
            User user = new User();
            user.setId(1L);

            UserOnboarding onboarding = new UserOnboarding(user, "TestDisplay");
            onboarding.markCompleted();

            OnboardingResponse response = OnboardingResponse.completed(onboarding);

            assertThat(response.isNeedsOnboarding()).isFalse();
            assertThat(response.isCompleted()).isTrue();
            assertThat(response.getDisplayName()).isEqualTo("TestDisplay");
        }
    }
}
