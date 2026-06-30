package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the reported cross-provider account-takeover:
 * "I sign up with email/password, then sign in with Google with the SAME email,
 * and I land on the SAME account - silently."
 *
 * Exercises the REAL prod login path (gateway → UserResolutionService.resolveUser).
 * Keycloak emitted a Google token with a *new* sub; the app must NOT rebind the
 * existing email/password account onto it. The legitimate Keycloak user-recreation
 * case (same provider, new sub) must still work.
 *
 * Before the fix, {@code createUserFromKeycloakJwt} looked the user up by EMAIL
 * alone and rebound providerId provider-blind - the first test below FAILED then.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Regression - no cross-provider silent account takeover by email")
class CrossProviderEmailMergeReproTest {

    @Mock private UserRepository userRepository;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private CreditService creditService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private CreditAttributionService creditAttributionService;

    private UserResolutionService service;

    private static final String EMAIL = "victim@example.com";
    private static final String PASSWORD_SUB = "kc-sub-PASSWORD-account-1111";
    private static final String GOOGLE_SUB = "kc-sub-GOOGLE-identity-2222";
    private static final String NEW_KC_SUB = "kc-sub-RECREATED-3333";

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(
                userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository,
                billingCustomerRepository, planRepository, creditAttributionService,
                new PlanStorageQuotaSyncer(null, null));
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    @DisplayName("Google login (new KC sub, same email) is DENIED and does NOT take over the email/password account")
    void googleLoginDoesNotTakeOverPasswordAccount() {
        User passwordAccount = existingAccount(1L, PASSWORD_SUB, AuthProvider.KEYCLOAK);
        // A Google brokered login carrying a DIFFERENT sub but the SAME email.
        String googleJwt = buildJwt(GOOGLE_SUB, EMAIL, "google");

        when(userRepository.findByProviderId(GOOGLE_SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordAccount));

        UserResolutionResponse resp = service.resolveUser(GOOGLE_SUB, googleJwt);

        // Fail closed: the cross-provider login is refused.
        assertThat(resp).as("cross-provider login must be denied, not merged").isNull();
        // The existing account is untouched - no rebind, no save.
        assertThat(passwordAccount.getProviderId()).isEqualTo(PASSWORD_SUB);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Keycloak user recreation (SAME provider, new sub, same email) still re-points the account")
    void keycloakUserRecreationStillRebinds() {
        User existing = existingAccount(7L, "old-kc-sub", AuthProvider.KEYCLOAK);
        // Direct Keycloak password login → NO identity_provider claim → KEYCLOAK.
        String recreatedJwt = buildJwt(NEW_KC_SUB, EMAIL, null);

        when(userRepository.findByProviderId(NEW_KC_SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(subscriptionRepository.findActiveByUserId(7L)).thenReturn(Optional.empty());
        lenient().when(planRepository.findByCode("FREE")).thenReturn(Optional.empty());
        lenient().when(onboardingService.needsOnboarding(anyString())).thenReturn(false);
        lenient().when(organizationService.getDefaultMembership(anyLong())).thenReturn(Optional.empty());

        UserResolutionResponse resp = service.resolveUser(NEW_KC_SUB, recreatedJwt);

        assertThat(resp).isNotNull();
        assertThat(resp.getUserId()).isEqualTo(7L);
        // Same-provider recreation: the existing row is re-pointed to the new sub.
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getProviderId()).isEqualTo(NEW_KC_SUB);
        assertThat(resp.getProviderId()).isEqualTo(NEW_KC_SUB);
    }

    private User existingAccount(Long id, String providerId, AuthProvider provider) {
        User u = new User();
        u.setId(id);
        u.setProviderId(providerId);
        u.setUsername("victim");
        u.setEmail(EMAIL);
        u.setAuthProvider(provider);
        u.setEnabled(true);
        u.setEmailVerified(false); // short-circuit credit attribution
        u.setRoles(Set.of("USER"));
        u.setUserVersion(1L);
        u.setCreatedAt(LocalDateTime.now().minusDays(3));
        u.setLastLoginAt(LocalDateTime.now().minusDays(3));
        return u;
    }

    private String buildJwt(String sub, String email, String identityProvider) {
        try {
            var header = new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256);
            var builder = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(sub).claim("email", email);
            if (identityProvider != null) {
                builder.claim("identity_provider", identityProvider);
            }
            var jwt = new com.nimbusds.jwt.SignedJWT(header, builder.build());
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner("super-secret-key-that-is-at-least-32-bytes-long!!"));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
