package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * The local {@code emailVerified} flag follows Keycloak's {@code email_verified} claim upward
 * on every resolve, not only when the row is created. Invitations, credits and notification
 * mail all gate on the local flag, so a user who verified in Keycloak after sign-up must not
 * stay "unverified" here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService - email_verified sync from the token")
class UserResolutionServiceEmailVerifiedSyncTest {

    private static final String PROVIDER_ID = "kc-sub-verify";
    private static final long USER_ID = 77L;

    @Mock private UserRepository userRepository;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private CreditService creditService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private FreeSubscriptionProvisioner freeSubscriptionProvisioner;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private AuthEventRecorder authEventRecorder;
    @Mock private com.apimarketplace.auth.lifecycle.UserLifecycleContextService lifecycleContext;

    private UserResolutionService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(
                userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository,
                billingCustomerRepository, planRepository, creditAttributionService,
                new PlanStorageQuotaSyncer(null, null), freeSubscriptionProvisioner);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "authEventRecorder", authEventRecorder);
        ReflectionTestUtils.setField(service, "lifecycleContext", lifecycleContext);

        user = new User();
        user.setId(USER_ID);
        user.setProviderId(PROVIDER_ID);
        user.setUsername("verifier");
        user.setEmail("ada@example.com");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setUserVersion(1L);
        user.setLastLoginAt(LocalDateTime.now().minusHours(3));

        lenient().when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(user));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.<Subscription>empty());
        lenient().when(onboardingService.needsOnboarding(anyString())).thenReturn(false);
        lenient().when(authEventRecorder.providerTag(any())).thenReturn("keycloak");
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any())).thenReturn(1);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("an EXISTING unverified account is upgraded when the token says email_verified=true for its address")
    void verifiedClaimUpgradesExistingAccount() {
        user.setEmailVerified(false);

        service.resolveUser(PROVIDER_ID, jwt("Ada@Example.com", true));

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository).save(user);
        // The transition is when the (write-once guarded) signup event is owed.
        verify(lifecycleContext).recordSignup(USER_ID);
    }

    @Test
    @DisplayName("a failing signup hand-off never undoes the flip nor fails the resolve")
    void failingSignupHandOffKeepsTheFlip() {
        user.setEmailVerified(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("queue down")).when(lifecycleContext).recordSignup(USER_ID);

        assertThat(service.resolveUser(PROVIDER_ID, jwt("ada@example.com", true))).isNotNull();

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("a false claim never downgrades a verified account")
    void falseClaimNeverDowngrades() {
        user.setEmailVerified(true);

        service.resolveUser(PROVIDER_ID, jwt("ada@example.com", false));

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("an ALREADY verified account gets no signup hand-off from the sync, even with a true claim")
    void alreadyVerifiedNoSignupHandOff() {
        user.setEmailVerified(true);

        service.resolveUser(PROVIDER_ID, jwt("ada@example.com", true));

        verify(lifecycleContext, never()).recordSignup(org.mockito.ArgumentMatchers.anyLong());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a missing claim leaves an unverified account unverified")
    void missingClaimDoesNothing() {
        user.setEmailVerified(false);

        service.resolveUser(PROVIDER_ID, jwt("ada@example.com", null));

        assertThat(user.isEmailVerified()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a verified claim for ANOTHER address (or a Unicode look-alike) does not upgrade this account")
    void mismatchedEmailDoesNotUpgrade() {
        user.setEmailVerified(false);

        service.resolveUser(PROVIDER_ID, jwt("someone-else@example.com", true));
        assertThat(user.isEmailVerified()).isFalse();

        user.setEmail("kate@example.com");
        service.resolveUser(PROVIDER_ID, jwt("\u212Aate@example.com", true));
        assertThat(user.isEmailVerified()).isFalse();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("the re-point path (same email + provider, new Keycloak subject) also upgrades the flag")
    void repointedAccountIsUpgraded() {
        user.setEmailVerified(false);
        String newSubject = "kc-sub-recreated";
        when(userRepository.findByProviderId(newSubject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        service.resolveUser(newSubject, jwt(newSubject, "ada@example.com", true));

        assertThat(user.getProviderId()).isEqualTo(newSubject);
        assertThat(user.isEmailVerified()).isTrue();
    }

    private String jwt(String email, Boolean emailVerified) {
        return jwt(PROVIDER_ID, email, emailVerified);
    }

    private String jwt(String subject, String email, Boolean emailVerified) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email);
        if (emailVerified != null) {
            claims.claim("email_verified", emailVerified);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner("super-secret-key-that-is-at-least-32-bytes-long!!"));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
