package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService Tests")
class UserResolutionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsernameValidator usernameValidator;

    @Mock
    private AgeValidator ageValidator;

    @Mock
    private OnboardingService onboardingService;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private CreditService creditService;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private BillingCustomerRepository billingCustomerRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private CreditAttributionService creditAttributionService;

    @Mock
    private OrganizationSamlLoginService samlLoginService;

    private UserResolutionService userResolutionService;

    @BeforeEach
    void setUp() {
        userResolutionService = new UserResolutionService(
                userRepository,
                creditService,
                usernameValidator,
                ageValidator,
                onboardingService,
                organizationService,
                subscriptionRepository,
                billingCustomerRepository,
                planRepository,
                creditAttributionService,
                new PlanStorageQuotaSyncer(null, null)
        );
        // Default stub for the atomic last-login update - most tests don't care
        // about the return value, they just need the call to not blow up. Tests
        // exercising the dedup logic specifically can override.
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any()))
                .thenReturn(1);
        // Self-injection in production goes through Spring proxy. In unit tests
        // we wire the same instance so updateLastLoginAtomic() is callable.
        ReflectionTestUtils.setField(userResolutionService, "self", userResolutionService);
        ReflectionTestUtils.setField(userResolutionService, "samlLoginService", samlLoginService);
    }

    // ===== Helper methods =====

    private User createTestUser(Long id, String providerId, String username) {
        User user = new User();
        user.setId(id);
        user.setProviderId(providerId);
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setUserVersion(1L);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private OrganizationMember createMembership(String orgUuid, OrganizationRole role) {
        Organization org = new Organization();
        org.setId(UUID.fromString(orgUuid));
        OrganizationMember member = new OrganizationMember();
        member.setOrganization(org);
        member.setRole(role);
        return member;
    }

    /**
     * Builds a minimal signed JWT for testing createUserFromKeycloakJwt().
     * Uses nimbus-jose-jwt to create a real JWT that can be parsed by SignedJWT.parse().
     */
    private String buildTestJwt(String sub, String email) {
        return buildTestJwt(sub, email, null);
    }

    private String buildTestJwt(String sub, String email, String provider) {
        return buildTestJwt(sub, email, provider, null);
    }

    private String buildTestJwtWithIdentityProvider(String sub, String email, String identityProvider) {
        return buildTestJwt(sub, email, null, identityProvider);
    }

    private String buildTestJwt(String sub, String email, String provider, String identityProvider) {
        try {
            com.nimbusds.jose.JWSHeader header = new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256);
            com.nimbusds.jwt.JWTClaimsSet.Builder claimsBuilder = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject(sub)
                    .claim("email", email);
            if (provider != null) {
                claimsBuilder.claim("provider", provider);
            }
            if (identityProvider != null) {
                claimsBuilder.claim("identity_provider", identityProvider);
            }
            com.nimbusds.jwt.JWTClaimsSet claims = claimsBuilder.build();
            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(header, claims);
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner(
                    "super-secret-key-that-is-at-least-32-bytes-long!!"
            ));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test JWT", e);
        }
    }

    // ===== resolveUser =====

    @Nested
    @DisplayName("resolveUser")
    class ResolveUser {

        @Test
        @DisplayName("should resolve existing user with complete response")
        void shouldResolveExistingUser() {
            User user = createTestUser(1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "testuser");

            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);
            String orgUuid = "a1a1a1a1-b2b2-c3c3-d4d4-e5e5e5e5e5e5";
            when(organizationService.getDefaultMembership(1L))
                    .thenReturn(Optional.of(createMembership(orgUuid, OrganizationRole.OWNER)));

            UserResolutionResponse response = userResolutionService.resolveUser("f47ac10b-58cc-4372-a567-0e02b2c3d479", null);

            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getProviderId()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
            assertThat(response.getEmail()).isEqualTo("testuser@test.com");
            assertThat(response.getPlan()).isEqualTo("FREE");
            assertThat(response.getRoles()).contains("USER");
            assertThat(response.getUserVersion()).isEqualTo(1L);
            assertThat(response.isActive()).isTrue();
            assertThat(response.isNeedsOnboarding()).isFalse();
            assertThat(response.isFirstLogin()).isFalse();
            assertThat(response.isProfileIncomplete()).isFalse();
            assertThat(response.getDefaultOrganizationId()).isEqualTo(orgUuid);
        }

        @Test
        @DisplayName("samlLoginEnsuresWorkspaceMembershipBeforeResponse")
        void samlLoginEnsuresWorkspaceMembershipBeforeResponse() {
            String providerId = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
            String alias = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";
            User user = createTestUser(1L, providerId, "samluser");
            String jwt = buildTestJwtWithIdentityProvider(providerId, "samluser@test.com", alias);

            when(userRepository.findByProviderId(providerId)).thenReturn(Optional.of(user));
            when(onboardingService.needsOnboarding(providerId)).thenReturn(false);
            when(organizationService.getDefaultMembership(1L)).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser(providerId, jwt);

            assertThat(response).isNotNull();
            verify(samlLoginService).ensureMembershipForIdentityProvider(user, alias);
        }

        @Test
        @DisplayName("samlWorkspaceJoinFailureFailsUserResolution")
        void samlWorkspaceJoinFailureFailsUserResolution() {
            String providerId = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
            String alias = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";
            User user = createTestUser(1L, providerId, "samluser");
            String jwt = buildTestJwtWithIdentityProvider(providerId, "samluser@test.com", alias);

            when(userRepository.findByProviderId(providerId)).thenReturn(Optional.of(user));
            doThrow(new RuntimeException("team status unavailable"))
                    .when(samlLoginService)
                    .ensureMembershipForIdentityProvider(user, alias);

            UserResolutionResponse response = userResolutionService.resolveUser(providerId, jwt);

            assertThat(response).isNull();
            verify(userRepository, never()).updateLastLoginIfStale(anyLong(), any(), any());
            verify(onboardingService, never()).needsOnboarding(anyString());
        }

        @Test
        @DisplayName("Should resolve legacy CE tokens whose subject is the numeric user id")
        void shouldResolveLegacyCeNumericSubjectToken() {
            User user = createTestUser(7L, "local:local@test.com", "localuser");
            user.setAuthProvider(AuthProvider.LOCAL);
            String token = buildTestJwt("7", "local@test.com", "local");

            when(userRepository.findByProviderId("7")).thenReturn(Optional.empty());
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));
            when(onboardingService.needsOnboarding("local:local@test.com")).thenReturn(false);
            when(organizationService.getDefaultMembership(7L)).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser("7", token);

            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(7L);
            assertThat(response.getProviderId()).isEqualTo("local:local@test.com");
            verify(userRepository, never()).save(argThat(saved -> "7".equals(saved.getProviderId())));
        }

        @Test
        @DisplayName("should return null when user not found and no JWT provided")
        void shouldReturnNullWhenUserNotFoundNoJwt() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser("00000000-0000-0000-0000-000000000000", null);

            assertThat(response).isNull();
        }

        @Test
        @DisplayName("should indicate onboarding needed for new user")
        void shouldIndicateOnboardingNeeded() {
            User user = createTestUser(2L, "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "newuser");

            when(userRepository.findByProviderId("a1b2c3d4-e5f6-7890-abcd-ef1234567890")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("a1b2c3d4-e5f6-7890-abcd-ef1234567890")).thenReturn(true);
            when(organizationService.getDefaultMembership(2L)).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser("a1b2c3d4-e5f6-7890-abcd-ef1234567890", null);

            assertThat(response).isNotNull();
            assertThat(response.isNeedsOnboarding()).isTrue();
            assertThat(response.isFirstLogin()).isTrue();
            assertThat(response.isProfileIncomplete()).isTrue();
        }

        @Test
        @DisplayName("should generate username for user without one")
        void shouldGenerateUsernameForUserWithoutOne() {
            User user = createTestUser(3L, "b2c3d4e5-f6a7-8901-bcde-f12345678901", null);

            when(userRepository.findByProviderId("b2c3d4e5-f6a7-8901-bcde-f12345678901")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("b2c3d4e5-f6a7-8901-bcde-f12345678901")).thenReturn(false);
            when(organizationService.getDefaultMembership(3L)).thenReturn(Optional.empty());
            when(usernameValidator.buildUsernameFromProviderId("b2c3d4e5-f6a7-8901-bcde-f12345678901")).thenReturn("id_nousername");
            when(usernameValidator.generateUniqueUsername("id_nousername")).thenReturn("id_nousername");

            userResolutionService.resolveUser("b2c3d4e5-f6a7-8901-bcde-f12345678901", null);

            // Should have saved at least once to set the username. The lastLogin
            // update no longer goes through save() - it uses the atomic
            // updateLastLoginIfStale query instead.
            verify(userRepository, atLeastOnce()).save(any(User.class));
            verify(userRepository).updateLastLoginIfStale(eq(3L), any(), any());
            verify(usernameValidator).generateUniqueUsername("id_nousername");
        }

        @Test
        @DisplayName("should handle null default organization gracefully")
        void shouldHandleNullDefaultOrganization() {
            User user = createTestUser(1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "testuser");

            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);
            when(organizationService.getDefaultMembership(1L)).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser("f47ac10b-58cc-4372-a567-0e02b2c3d479", null);

            assertThat(response).isNotNull();
            assertThat(response.getDefaultOrganizationId()).isNull();
        }

        @Test
        @DisplayName("should handle organization service exception gracefully")
        void shouldHandleOrgServiceExceptionGracefully() {
            User user = createTestUser(1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "testuser");

            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);
            when(organizationService.getDefaultMembership(1L)).thenThrow(new RuntimeException("DB error"));

            UserResolutionResponse response = userResolutionService.resolveUser("f47ac10b-58cc-4372-a567-0e02b2c3d479", null);

            // Should still return a response, just without org ID
            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should handle concurrent user creation via catch-and-retry")
        void shouldHandleConcurrentUserCreation() {
            // Simulate race condition: first findByProviderId returns empty,
            // save throws DataIntegrityViolationException (another thread created it),
            // second findByProviderId returns the user created by the other thread.
            String providerId = "race-condition-provider-id";
            User user = createTestUser(1L, providerId, "raceuser");

            // Build a minimal valid JWT for createUserFromKeycloakJwt
            // We use a real signed JWT structure for the test
            String fakeJwt = buildTestJwt(providerId, "race@test.com");

            // First call: user not found
            // After DataIntegrityViolationException, retry: user found
            when(userRepository.findByProviderId(providerId))
                    .thenReturn(Optional.empty())  // first call in resolveUser
                    .thenReturn(Optional.of(user)); // retry after DataIntegrityViolationException

            // save() throws DataIntegrityViolationException (duplicate key)
            lenient().when(userRepository.save(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"))
                    .thenReturn(user); // for lastLoginAt update

            when(userRepository.findByEmail("race@test.com")).thenReturn(Optional.empty());
            when(usernameValidator.buildUsernameFromProviderId(providerId)).thenReturn("raceuser");
            when(usernameValidator.generateUniqueUsername("raceuser")).thenReturn("raceuser");
            when(onboardingService.needsOnboarding(providerId)).thenReturn(false);
            when(organizationService.getDefaultMembership(1L)).thenReturn(Optional.empty());

            UserResolutionResponse response = userResolutionService.resolveUser(providerId, fakeJwt);

            // Should succeed despite the race condition
            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
            // findByProviderId called twice: once initially, once as retry
            verify(userRepository, times(2)).findByProviderId(providerId);
        }
    }

    // ===== canUserMakeRequest =====

    @Nested
    @DisplayName("canUserMakeRequest")
    class CanUserMakeRequest {

        @Test
        @DisplayName("should return true when user has tokens and requests")
        void shouldReturnTrueWhenUserHasQuotas() {
            User user = createTestUser(1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "testuser");

            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);
            when(onboardingService.needsOnboarding("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(false);
            when(organizationService.getDefaultMembership(1L)).thenReturn(Optional.empty());

            boolean result = userResolutionService.canUserMakeRequest("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user not found")
        void shouldReturnFalseWhenUserNotFound() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());

            boolean result = userResolutionService.canUserMakeRequest("00000000-0000-0000-0000-000000000000");

            assertThat(result).isFalse();
        }
    }

    // ===== updateUserVersion =====

    @Nested
    @DisplayName("updateUserVersion")
    class UpdateUserVersion {

        @Test
        @DisplayName("should increment user version")
        void shouldIncrementUserVersion() {
            User user = createTestUser(1L, "f47ac10b-58cc-4372-a567-0e02b2c3d479", "testuser");
            user.setUserVersion(5L);

            when(userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479")).thenReturn(Optional.of(user));
            lenient().when(userRepository.save(any(User.class))).thenReturn(user);

            Long newVersion = userResolutionService.updateUserVersion("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(newVersion).isEqualTo(6L);
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("should return null when user not found")
        void shouldReturnNullWhenUserNotFound() {
            when(userRepository.findByProviderId("00000000-0000-0000-0000-000000000000")).thenReturn(Optional.empty());

            Long newVersion = userResolutionService.updateUserVersion("00000000-0000-0000-0000-000000000000");

            assertThat(newVersion).isNull();
            verify(userRepository, never()).save(any());
        }
    }

}
