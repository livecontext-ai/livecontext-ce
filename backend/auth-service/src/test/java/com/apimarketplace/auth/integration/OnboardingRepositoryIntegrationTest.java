package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for UserOnboardingRepository.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@AutoConfigureTestEntityManager
@DisplayName("Onboarding Repository Integration Tests")
class OnboardingRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserOnboardingRepository onboardingRepository;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        testUser = new User("onboarduser", "onboard@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setEnabled(true);
        testUser.setUserVersion(1L);
        testUser = entityManager.persistAndFlush(testUser);

        otherUser = new User("otheruser", "other@example.com", AuthProvider.KEYCLOAK, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        otherUser.setEnabled(true);
        otherUser.setUserVersion(1L);
        otherUser = entityManager.persistAndFlush(otherUser);
    }

    @Nested
    @DisplayName("Basic CRUD")
    class BasicCrud {

        @Test
        @DisplayName("Should save and retrieve onboarding by user ID")
        void shouldSaveAndFindByUserId() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.setProfession("Developer");
            onboarding.setCompanySize("startup");
            onboarding.setExperienceLevel("advanced");
            entityManager.persistAndFlush(onboarding);

            Optional<UserOnboarding> found = onboardingRepository.findByUserId(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getDisplayName()).isEqualTo("TestDisplay");
            assertThat(found.get().getProfession()).isEqualTo("Developer");
            assertThat(found.get().getCompanySize()).isEqualTo("startup");
            assertThat(found.get().getExperienceLevel()).isEqualTo("advanced");
        }

        @Test
        @DisplayName("Should find onboarding by user provider ID")
        void shouldFindByUserProviderId() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "ProviderDisplay");
            entityManager.persistAndFlush(onboarding);

            Optional<UserOnboarding> found = onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(found).isPresent();
            assertThat(found.get().getDisplayName()).isEqualTo("ProviderDisplay");
        }

        @Test
        @DisplayName("Should return empty for non-existent user provider ID")
        void shouldReturnEmptyForNonExistentProviderId() {
            Optional<UserOnboarding> found = onboardingRepository.findByUserProviderId("00000000-0000-0000-0000-000000000000");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Should check onboarding existence by user ID")
        void shouldCheckExistenceByUserId() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "ExistCheck");
            entityManager.persistAndFlush(onboarding);

            assertThat(onboardingRepository.existsByUserId(testUser.getId())).isTrue();
            assertThat(onboardingRepository.existsByUserId(999L)).isFalse();
        }

        @Test
        @DisplayName("Should round-trip onboarding list fields as JSON")
        void shouldRoundTripJsonListFields() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "JsonLists");
            onboarding.setInterests(List.of("automation", "integrations"));
            onboarding.setUseCases(List.of("workflow-automation", "data-integration"));
            entityManager.persistAndFlush(onboarding);
            entityManager.clear();

            Optional<UserOnboarding> found = onboardingRepository.findByUserId(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getInterests()).containsExactly("automation", "integrations");
            assertThat(found.get().getUseCases()).containsExactly("workflow-automation", "data-integration");
        }
    }

    @Nested
    @DisplayName("Display Name Uniqueness")
    class DisplayNameUniqueness {

        @Test
        @DisplayName("Should detect existing display name (case-insensitive)")
        void shouldDetectExistingDisplayNameCaseInsensitive() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "UniqueDisplay");
            entityManager.persistAndFlush(onboarding);

            // Check case-insensitive match
            boolean exists = onboardingRepository.existsByDisplayNameIgnoreCase("uniquedisplay");
            assertThat(exists).isTrue();

            boolean existsUpperCase = onboardingRepository.existsByDisplayNameIgnoreCase("UNIQUEDISPLAY");
            assertThat(existsUpperCase).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-taken display name")
        void shouldReturnFalseForAvailableDisplayName() {
            boolean exists = onboardingRepository.existsByDisplayNameIgnoreCase("AvailableName");
            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("Should exclude current user when checking display name uniqueness")
        void shouldExcludeCurrentUserFromDisplayNameCheck() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "MyDisplay");
            entityManager.persistAndFlush(onboarding);

            // Same display name but excluded for the current user
            boolean takenByOther = onboardingRepository
                    .existsByDisplayNameIgnoreCaseAndUserIdNot("MyDisplay", testUser.getId());
            assertThat(takenByOther).isFalse();

            // When checking for a different user, should be taken
            boolean takenForOtherUser = onboardingRepository
                    .existsByDisplayNameIgnoreCaseAndUserIdNot("MyDisplay", otherUser.getId());
            assertThat(takenForOtherUser).isTrue();
        }
    }

    @Nested
    @DisplayName("Onboarding Business Logic")
    class OnboardingBusinessLogic {

        @Test
        @DisplayName("Should mark onboarding as completed")
        void shouldMarkOnboardingAsCompleted() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "CompleteUser");
            onboarding.markCompleted();
            entityManager.persistAndFlush(onboarding);
            entityManager.clear();

            Optional<UserOnboarding> found = onboardingRepository.findByUserId(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().isOnboardingCompleted()).isTrue();
            assertThat(found.get().getCompletedAt()).isNotNull();
            assertThat(found.get().needsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("Should mark onboarding as skipped")
        void shouldMarkOnboardingAsSkipped() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "SkipUser");
            onboarding.markSkipped();
            entityManager.persistAndFlush(onboarding);
            entityManager.clear();

            Optional<UserOnboarding> found = onboardingRepository.findByUserId(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().isOnboardingSkipped()).isTrue();
            assertThat(found.get().getCompletedAt()).isNotNull();
            assertThat(found.get().needsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("New onboarding should need onboarding")
        void newOnboardingShouldNeedOnboarding() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "NewUser");
            entityManager.persistAndFlush(onboarding);

            assertThat(onboarding.needsOnboarding()).isTrue();
            assertThat(onboarding.isOnboardingCompleted()).isFalse();
            assertThat(onboarding.isOnboardingSkipped()).isFalse();
        }

        @Test
        @DisplayName("Should track onboarding step progress")
        void shouldTrackOnboardingStepProgress() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "StepUser");
            onboarding.setOnboardingStep(2);
            entityManager.persistAndFlush(onboarding);
            entityManager.clear();

            Optional<UserOnboarding> found = onboardingRepository.findByUserId(testUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getOnboardingStep()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should set timestamps on creation and update")
        void shouldSetTimestampsCorrectly() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TimestampUser");
            entityManager.persistAndFlush(onboarding);

            assertThat(onboarding.getCreatedAt()).isNotNull();
            assertThat(onboarding.getUpdatedAt()).isNotNull();
        }
    }
}
