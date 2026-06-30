package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserOnboarding Domain Model Tests")
class UserOnboardingTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            UserOnboarding onboarding = new UserOnboarding();

            assertThat(onboarding.isOnboardingCompleted()).isFalse();
            assertThat(onboarding.isOnboardingSkipped()).isFalse();
            assertThat(onboarding.getOnboardingStep()).isZero();
            assertThat(onboarding.getCreatedAt()).isNotNull();
            assertThat(onboarding.getUpdatedAt()).isNotNull();
            assertThat(onboarding.getInterests()).isEmpty();
            assertThat(onboarding.getUseCases()).isEmpty();
        }

        @Test
        @DisplayName("should create with user and display name")
        void shouldCreateWithUserAndDisplayName() {
            User user = new User();
            user.setId(1L);

            UserOnboarding onboarding = new UserOnboarding(user, "TestUser");

            assertThat(onboarding.getUser()).isEqualTo(user);
            assertThat(onboarding.getDisplayName()).isEqualTo("TestUser");
            assertThat(onboarding.isOnboardingCompleted()).isFalse();
        }
    }

    @Nested
    @DisplayName("markCompleted()")
    class MarkCompletedTests {

        @Test
        @DisplayName("should set onboardingCompleted to true")
        void shouldSetOnboardingCompletedToTrue() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markCompleted();

            assertThat(onboarding.isOnboardingCompleted()).isTrue();
        }

        @Test
        @DisplayName("should set completedAt")
        void shouldSetCompletedAt() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markCompleted();

            assertThat(onboarding.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("markSkipped()")
    class MarkSkippedTests {

        @Test
        @DisplayName("should set onboardingSkipped to true")
        void shouldSetOnboardingSkippedToTrue() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markSkipped();

            assertThat(onboarding.isOnboardingSkipped()).isTrue();
        }

        @Test
        @DisplayName("should set completedAt")
        void shouldSetCompletedAt() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markSkipped();

            assertThat(onboarding.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("needsOnboarding()")
    class NeedsOnboardingTests {

        @Test
        @DisplayName("should return true when not completed and not skipped")
        void shouldReturnTrueWhenNotCompletedAndNotSkipped() {
            UserOnboarding onboarding = new UserOnboarding();

            assertThat(onboarding.needsOnboarding()).isTrue();
        }

        @Test
        @DisplayName("should return false when completed")
        void shouldReturnFalseWhenCompleted() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markCompleted();

            assertThat(onboarding.needsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("should return false when skipped")
        void shouldReturnFalseWhenSkipped() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markSkipped();

            assertThat(onboarding.needsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("should return false when both completed and skipped")
        void shouldReturnFalseWhenBothCompletedAndSkipped() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.markCompleted();
            onboarding.markSkipped();

            assertThat(onboarding.needsOnboarding()).isFalse();
        }
    }

    @Nested
    @DisplayName("setInterests()")
    class SetInterestsTests {

        @Test
        @DisplayName("should set interests from list")
        void shouldSetInterestsFromList() {
            UserOnboarding onboarding = new UserOnboarding();
            List<String> interests = Arrays.asList("ai", "automation", "data");

            onboarding.setInterests(interests);

            assertThat(onboarding.getInterests()).containsExactly("ai", "automation", "data");
        }

        @Test
        @DisplayName("should default to empty list when null is set")
        void shouldDefaultToEmptyListWhenNullIsSet() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.setInterests(null);

            assertThat(onboarding.getInterests()).isEmpty();
        }
    }

    @Nested
    @DisplayName("setUseCases()")
    class SetUseCasesTests {

        @Test
        @DisplayName("should set use cases from list")
        void shouldSetUseCasesFromList() {
            UserOnboarding onboarding = new UserOnboarding();
            List<String> useCases = Arrays.asList("api-testing", "chatbots");

            onboarding.setUseCases(useCases);

            assertThat(onboarding.getUseCases()).containsExactly("api-testing", "chatbots");
        }

        @Test
        @DisplayName("should default to empty list when null is set")
        void shouldDefaultToEmptyListWhenNullIsSet() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.setUseCases(null);

            assertThat(onboarding.getUseCases()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            UserOnboarding onboarding = new UserOnboarding();
            onboarding.setDisplayName("TestUser");
            onboarding.setProfession("Developer");

            String result = onboarding.toString();

            assertThat(result).contains("displayName='TestUser'");
            assertThat(result).contains("profession='Developer'");
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            UserOnboarding onboarding = new UserOnboarding();

            onboarding.setId(1L);
            onboarding.setDisplayName("Test User");
            onboarding.setProfession("Developer");
            onboarding.setCompanySize("startup");
            onboarding.setExperienceLevel("intermediate");
            onboarding.setOnboardingStep(2);

            assertThat(onboarding.getId()).isEqualTo(1L);
            assertThat(onboarding.getDisplayName()).isEqualTo("Test User");
            assertThat(onboarding.getProfession()).isEqualTo("Developer");
            assertThat(onboarding.getCompanySize()).isEqualTo("startup");
            assertThat(onboarding.getExperienceLevel()).isEqualTo("intermediate");
            assertThat(onboarding.getOnboardingStep()).isEqualTo(2);
        }
    }
}
