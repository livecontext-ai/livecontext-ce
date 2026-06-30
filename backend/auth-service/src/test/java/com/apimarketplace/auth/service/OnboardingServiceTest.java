package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.dto.OnboardingRequest;
import com.apimarketplace.auth.dto.OnboardingResponse;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.auth.UserSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingService Tests")
class OnboardingServiceTest {

    @Mock
    private UserOnboardingRepository onboardingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private GatewayCacheClient gatewayCacheClient;

    @InjectMocks
    private OnboardingService onboardingService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setEmail("test@example.com");
        testUser.setEmailVerified(true); // Default to verified for most tests
    }

    @Nested
    @DisplayName("resolveDisplayNames()")
    class ResolveDisplayNames {

        @Test
        @DisplayName("uses user_onboarding.display_name (the sidebar name), falling back to full name for users without one")
        void resolvesViaOnboardingThenFallback() {
            User u1 = new User();
            u1.setId(1L);
            UserOnboarding ob1 = new UserOnboarding(u1, "ada lovelace");
            User u5 = new User();
            u5.setId(5L);
            u5.setFirstName("Live");
            u5.setLastName("Context");

            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of(ob1));
            when(userRepository.findAllById(anySet())).thenReturn(List.of(u5));

            Map<Long, String> result = onboardingService.resolveDisplayNames(Set.of(1L, 5L));

            assertThat(result).containsEntry(1L, "ada lovelace").containsEntry(5L, "Live Context");
        }

        @Test
        @DisplayName("empty input returns an empty map without hitting the database")
        void emptyInput() {
            assertThat(onboardingService.resolveDisplayNames(List.of())).isEmpty();
            verifyNoInteractions(onboardingRepository, userRepository);
        }
    }

    @Nested
    @DisplayName("resolveDisplayName() - single user, same fallback chain")
    class ResolveDisplayName {

        @Test
        @DisplayName("uses the onboarding display_name when one is set")
        void usesOnboardingDisplayName() {
            User u = new User();
            u.setId(1L);
            when(onboardingRepository.findAllByUserIdIn(anySet()))
                    .thenReturn(List.of(new UserOnboarding(u, "ada lovelace")));

            assertThat(onboardingService.resolveDisplayName(1L)).isEqualTo("ada lovelace");
        }

        @Test
        @DisplayName("CE regression: no onboarding row → full name from the User row, NOT \"Unknown\"")
        void fallsBackToFullNameWhenNoOnboarding() {
            User u = new User();
            u.setId(1L);
            u.setFirstName("Live");
            u.setLastName("Context");
            // CE embedded users have no user_onboarding row at all.
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet())).thenReturn(List.of(u));

            String name = onboardingService.resolveDisplayName(1L);

            assertThat(name).isEqualTo("Live Context");
            assertThat(name).isNotEqualTo("Unknown");
        }

        @Test
        @DisplayName("no onboarding and no name → email as the last fallback")
        void fallsBackToEmailWhenNoName() {
            User u = new User();
            u.setId(1L);
            u.setEmail("solo@example.com");
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet())).thenReturn(List.of(u));

            assertThat(onboardingService.resolveDisplayName(1L)).isEqualTo("solo@example.com");
        }

        @Test
        @DisplayName("null userId returns null without hitting the database")
        void nullUserId() {
            assertThat(onboardingService.resolveDisplayName(null)).isNull();
            verifyNoInteractions(onboardingRepository, userRepository);
        }

        @Test
        @DisplayName("unknown user id (no row) returns null")
        void unknownUserId() {
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet())).thenReturn(List.of());

            assertThat(onboardingService.resolveDisplayName(99L)).isNull();
        }
    }

    @Nested
    @DisplayName("resolveUserSummaries() - batch, both id forms, used by /users/resolve-batch")
    class ResolveUserSummaries {

        private User numericUser(long id, String first, String last, String username, String email) {
            User u = new User();
            u.setId(id);
            u.setFirstName(first);
            u.setLastName(last);
            u.setUsername(username);
            u.setEmail(email);
            return u;
        }

        @Test
        @DisplayName("numeric id: onboarding display name wins over the users-row fallback")
        void numericOnboardingNameWins() {
            User u = new User();
            u.setId(7L);
            u.setFirstName("Should");
            u.setLastName("NotUse");
            UserOnboarding ob = new UserOnboarding(u, "Ada Lovelace");
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of(ob));

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of("7"));

            assertThat(result).containsKey("7");
            assertThat(result.get("7").displayName()).isEqualTo("Ada Lovelace");
            // No onboarding gap → the users-row fallback query never runs.
            verify(userRepository, never()).findAllById(anySet());
        }

        @Test
        @DisplayName("numeric id with NO onboarding row falls back to the users-row full name (the CE embedded case)")
        void numericFallbackToFullName() {
            // CE embedded: register() creates the users row but NEVER a
            // user_onboarding row, so onboarding returns nothing and the actor
            // must resolve via the users-row identity instead of "unknown".
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet()))
                    .thenReturn(List.of(numericUser(42L, "Ada", "Lovelace", "ada", "ada@example.com")));

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of("42"));

            assertThat(result.get("42").displayName()).isEqualTo("Ada Lovelace");
        }

        @Test
        @DisplayName("blank onboarding display name is treated as missing → users-row fallback applies")
        void blankOnboardingNameFallsBack() {
            User u = new User();
            u.setId(9L);
            UserOnboarding ob = new UserOnboarding(u, "   "); // blank
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of(ob));
            when(userRepository.findAllById(anySet()))
                    .thenReturn(List.of(numericUser(9L, null, null, "ninja", "ninja@example.com")));

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of("9"));

            // No first/last name → username is the next fallback rung.
            assertThat(result.get("9").displayName()).isEqualTo("ninja");
        }

        @Test
        @DisplayName("provider id (Keycloak sub) with no onboarding falls back to users-row identity (cloud edge case)")
        void providerFormResolves() {
            String sub = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
            User u = new User();
            u.setId(3L);
            u.setProviderId(sub);
            u.setEmail("cloud@example.com");
            // No onboarding row for this sub → fallback to the users-row identity
            // (firstName/lastName come from the OIDC profile in cloud).
            when(onboardingRepository.findAllByUserProviderIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findByProviderIdIn(anySet())).thenReturn(List.of(u));

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of(sub));

            // No name/username → email is the last rung.
            assertThat(result.get(sub).displayName()).isEqualTo("cloud@example.com");
        }

        @Test
        @DisplayName("mixed numeric + provider ids resolve in one call, keyed by the exact input string")
        void mixedFormsInOneCall() {
            User numeric = numericUser(11L, "Grace", "Hopper", "grace", "grace@example.com");
            String sub = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            User provider = new User();
            provider.setId(12L);
            provider.setProviderId(sub);
            UserOnboarding providerOb = new UserOnboarding(provider, "Alan Turing");

            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet())).thenReturn(List.of(numeric));
            when(onboardingRepository.findAllByUserProviderIdIn(anySet())).thenReturn(List.of(providerOb));

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of("11", sub));

            assertThat(result.get("11").displayName()).isEqualTo("Grace Hopper");
            assertThat(result.get(sub).displayName()).isEqualTo("Alan Turing");
        }

        @Test
        @DisplayName("id with neither an onboarding name nor a users row is absent (truly unknown)")
        void unknownIdAbsent() {
            when(onboardingRepository.findAllByUserIdIn(anySet())).thenReturn(List.of());
            when(userRepository.findAllById(anySet())).thenReturn(List.of());

            Map<String, UserSummaryDto> result = onboardingService.resolveUserSummaries(List.of("404"));

            assertThat(result).doesNotContainKey("404");
        }

        @Test
        @DisplayName("empty input returns an empty map without hitting the database")
        void emptyInput() {
            assertThat(onboardingService.resolveUserSummaries(List.of())).isEmpty();
            verifyNoInteractions(onboardingRepository, userRepository);
        }
    }

    @Nested
    @DisplayName("getOnboardingStatus()")
    class GetOnboardingStatusTests {

        @Test
        @DisplayName("should return needsOnboarding when no onboarding found")
        void shouldReturnNeedsOnboardingWhenNotFound() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.empty());

            OnboardingResponse response = onboardingService.getOnboardingStatus("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(response.isNeedsOnboarding()).isTrue();
            assertThat(response.isCompleted()).isFalse();
            assertThat(response.isSkipped()).isFalse();
            assertThat(response.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("should return existing onboarding data when found")
        void shouldReturnExistingOnboardingData() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.setOnboardingStep(2);
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            OnboardingResponse response = onboardingService.getOnboardingStatus("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(response.getDisplayName()).isEqualTo("TestDisplay");
            assertThat(response.getCurrentStep()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return completed status when onboarding is completed")
        void shouldReturnCompletedStatus() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.markCompleted();
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            OnboardingResponse response = onboardingService.getOnboardingStatus("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(response.isCompleted()).isTrue();
            assertThat(response.isNeedsOnboarding()).isFalse();
        }

        @Test
        @DisplayName("should force needsOnboarding when email not verified")
        void shouldForceNeedsOnboardingWhenEmailNotVerified() {
            testUser.setEmailVerified(false);
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.markCompleted();
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            OnboardingResponse response = onboardingService.getOnboardingStatus("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(response.isNeedsOnboarding()).isTrue();
            assertThat(response.isEmailVerified()).isFalse();
        }

    }

    @Nested
    @DisplayName("needsOnboarding()")
    class NeedsOnboardingTests {

        @Test
        @DisplayName("should return true when no onboarding found")
        void shouldReturnTrueWhenNoOnboardingFound() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.empty());

            boolean result = onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when onboarding is completed")
        void shouldReturnFalseWhenCompleted() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.markCompleted();
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            boolean result = onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when onboarding is skipped")
        void shouldReturnFalseWhenSkipped() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.markSkipped();
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            boolean result = onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true when onboarding is in progress")
        void shouldReturnTrueWhenInProgress() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));

            boolean result = onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true when email is not verified even if onboarding completed")
        void shouldReturnTrueWhenEmailNotVerified() {
            testUser.setEmailVerified(false);
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            onboarding.markCompleted();
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));

            boolean result = onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isTrue();
        }

    }

    @Nested
    @DisplayName("saveOnboarding()")
    class SaveOnboardingTests {

        @Test
        @DisplayName("should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());

            OnboardingRequest request = new OnboardingRequest("TestDisplay");

            assertThatThrownBy(() -> onboardingService.saveOnboarding("00000000-0000-0000-0000-000000000000", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("should throw when display name is taken")
        void shouldThrowWhenDisplayNameIsTaken() {
            UserOnboarding existing = new UserOnboarding(testUser, "OldName");
            existing.setId(1L);
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot("TakenName", 1L)).thenReturn(true);

            OnboardingRequest request = new OnboardingRequest("TakenName");

            assertThatThrownBy(() -> onboardingService.saveOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Display name is already taken");
        }

        @Test
        @DisplayName("should save onboarding data successfully")
        void shouldSaveOnboardingDataSuccessfully() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.empty());
            // No need to stub existsByDisplayNameIgnoreCase: when findByUserId returns empty,
            // a new UserOnboarding is created with displayName="NewName", so request.getDisplayName()
            // equals onboarding.getDisplayName() and the uniqueness check is skipped.
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingRequest request = new OnboardingRequest("NewName");
            request.setProfession("Developer");
            request.setCompanySize("startup");
            request.setInterests(Arrays.asList("ai", "automation"));
            request.setCurrentStep(2);

            OnboardingResponse response = onboardingService.saveOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request);

            assertThat(response).isNotNull();
            verify(onboardingRepository).save(any(UserOnboarding.class));
        }

        @Test
        @DisplayName("should update existing onboarding data")
        void shouldUpdateExistingOnboardingData() {
            UserOnboarding existing = new UserOnboarding(testUser, "OldName");
            existing.setId(1L);
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot("NewName", 1L)).thenReturn(false);
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingRequest request = new OnboardingRequest("NewName");

            OnboardingResponse response = onboardingService.saveOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request);

            assertThat(response).isNotNull();
            verify(onboardingRepository).save(any(UserOnboarding.class));
        }

        @Test
        @DisplayName("should handle empty string profession by converting to null")
        void shouldHandleEmptyStringProfession() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingRequest request = new OnboardingRequest("TestName");
            request.setProfession("  ");
            request.setCompanySize("");
            request.setExperienceLevel("  ");

            onboardingService.saveOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request);

            verify(onboardingRepository).save(any(UserOnboarding.class));
        }
    }

    @Nested
    @DisplayName("completeOnboarding()")
    class CompleteOnboardingTests {

        @Test
        @DisplayName("should complete onboarding and create organization")
        void shouldCompleteOnboardingAndCreateOrganization() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingRequest request = new OnboardingRequest("TestDisplay");

            OnboardingResponse response = onboardingService.completeOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request);

            assertThat(response.isCompleted()).isTrue();
            verify(organizationService).createPersonalOrganization(eq(testUser), eq("TestDisplay"));
            verify(gatewayCacheClient).invalidateUserCache("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("should reject complete when email not verified")
        void shouldRejectCompleteWhenEmailNotVerified() {
            testUser.setEmailVerified(false);
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));

            OnboardingRequest request = new OnboardingRequest("TestDisplay");

            assertThatThrownBy(() -> onboardingService.completeOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email must be verified");
        }

        @Test
        @DisplayName("should complete onboarding for CE local user after registration marks email verified")
        void shouldCompleteOnboardingForCeLocalUserAfterRegistrationMarksEmailVerified() {
            testUser.setAuthProvider(AuthProvider.LOCAL);
            testUser.setEmailVerified(true);
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingResponse response = onboardingService.completeOnboarding(
                    "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                    new OnboardingRequest("TestDisplay"));

            assertThat(response.isCompleted()).isTrue();
            assertThat(response.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("should not fail if organization creation fails")
        void shouldNotFailIfOrganizationCreationFails() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.findByUserProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("Org creation failed")).when(organizationService)
                    .createPersonalOrganization(any(User.class), anyString());

            OnboardingRequest request = new OnboardingRequest("TestDisplay");

            // Should not throw
            OnboardingResponse response = onboardingService.completeOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", request);

            assertThat(response).isNotNull();
            verify(gatewayCacheClient, never()).invalidateUserCache(anyString());
        }
    }

    @Nested
    @DisplayName("skipOnboarding()")
    class SkipOnboardingTests {

        @Test
        @DisplayName("should throw when display name is null")
        void shouldThrowWhenDisplayNameIsNull() {
            assertThatThrownBy(() -> onboardingService.skipOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Display name is required");
        }

        @Test
        @DisplayName("should throw when display name is blank")
        void shouldThrowWhenDisplayNameIsBlank() {
            assertThatThrownBy(() -> onboardingService.skipOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Display name is required");
        }

        @Test
        @DisplayName("should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> onboardingService.skipOnboarding("00000000-0000-0000-0000-000000000000", "TestName"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("should throw when display name is taken")
        void shouldThrowWhenDisplayNameIsTaken() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(onboardingRepository.existsByDisplayNameIgnoreCase("TakenName")).thenReturn(true);

            assertThatThrownBy(() -> onboardingService.skipOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", "TakenName"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Display name is already taken");
        }

        @Test
        @DisplayName("should skip onboarding successfully")
        void shouldSkipOnboardingSuccessfully() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(onboardingRepository.existsByDisplayNameIgnoreCase("SkipName")).thenReturn(false);
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));

            OnboardingResponse response = onboardingService.skipOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", "SkipName");

            assertThat(response).isNotNull();
            verify(organizationService).createPersonalOrganization(eq(testUser), eq("SkipName"));
            verify(gatewayCacheClient).invalidateUserCache("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("should reject skip when email not verified")
        void shouldRejectSkipWhenEmailNotVerified() {
            testUser.setEmailVerified(false);
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() -> onboardingService.skipOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479", "TestName"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email must be verified");
        }
    }

    @Nested
    @DisplayName("isDisplayNameAvailable()")
    class IsDisplayNameAvailableTests {

        @Test
        @DisplayName("should return false for null display name")
        void shouldReturnFalseForNullDisplayName() {
            boolean result = onboardingService.isDisplayNameAvailable(null, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for blank display name")
        void shouldReturnFalseForBlankDisplayName() {
            boolean result = onboardingService.isDisplayNameAvailable("  ", "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should check without user exclusion when user not found")
        void shouldCheckWithoutUserExclusionWhenUserNotFound() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());
            when(onboardingRepository.existsByDisplayNameIgnoreCase("TestName")).thenReturn(false);

            boolean result = onboardingService.isDisplayNameAvailable("TestName", "00000000-0000-0000-0000-000000000000");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should exclude current user from uniqueness check")
        void shouldExcludeCurrentUserFromUniquenessCheck() {
            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(testUser));
            when(onboardingRepository.existsByDisplayNameIgnoreCaseAndUserIdNot("TestName", 1L)).thenReturn(false);

            boolean result = onboardingService.isDisplayNameAvailable("TestName", "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("getOnboardingByUserId()")
    class GetOnboardingByUserIdTests {

        @Test
        @DisplayName("should return onboarding when found")
        void shouldReturnOnboardingWhenFound() {
            UserOnboarding onboarding = new UserOnboarding(testUser, "TestDisplay");
            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));

            Optional<UserOnboarding> result = onboardingService.getOnboardingByUserId(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getDisplayName()).isEqualTo("TestDisplay");
        }

        @Test
        @DisplayName("should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            when(onboardingRepository.findByUserId(99L)).thenReturn(Optional.empty());

            Optional<UserOnboarding> result = onboardingService.getOnboardingByUserId(99L);

            assertThat(result).isEmpty();
        }
    }
}
