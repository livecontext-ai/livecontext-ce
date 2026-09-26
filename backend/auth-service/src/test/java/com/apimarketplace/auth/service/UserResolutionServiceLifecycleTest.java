package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.lifecycle.LifecycleEmailService;
import com.apimarketplace.auth.lifecycle.LifecycleEvents;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two lifecycle signals born in user resolution: {@code user.signed_up} on account
 * creation and {@code user.returned} on a real login after a long absence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService - lifecycle signup and return signals")
class UserResolutionServiceLifecycleTest {

    private static final String PROVIDER_ID = "kc-sub-1234";
    private static final long USER_ID = 42L;
    private static final LocalDateTime AUTH_AT = LocalDateTime.of(2026, 9, 24, 10, 0);

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
    @Mock private LifecycleEmailService lifecycleEmails;
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
        ReflectionTestUtils.setField(service, "lifecycleEmails", lifecycleEmails);
        ReflectionTestUtils.setField(service, "lifecycleContext", lifecycleContext);

        user = new User();
        user.setId(USER_ID);
        user.setProviderId(PROVIDER_ID);
        user.setUsername("tester");
        user.setEmail("tester@test.com");
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
    }

    @Test
    @DisplayName("a brand-new account with a verified email (e.g. Google) sends user.signed_up through the write-once guard")
    void newAccountEmitsSignedUp() {
        when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User created = inv.getArgument(0);
            created.setId(USER_ID);
            return created;
        });

        service.resolveUser(PROVIDER_ID, jwt(null, true));

        verify(lifecycleContext).recordSignup(USER_ID);
        verify(lifecycleContext, never()).retrySignupIfUnsent(any());
        verify(lifecycleEmails, never()).syncContactAndEmit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Regression (double welcome): the loser of a first-login creation race also sees lastLoginAt == null but sends nothing")
    void creationRaceLoserSendsNothing() {
        // The winner inserted the row; the loser's insert hits the unique key and re-reads the
        // winner's row, still with lastLoginAt == null, so it too believes it created the account.
        User winnersRow = new User();
        winnersRow.setId(USER_ID);
        winnersRow.setProviderId(PROVIDER_ID);
        winnersRow.setUsername("tester");
        winnersRow.setEmail("tester@test.com");
        winnersRow.setAuthProvider(AuthProvider.KEYCLOAK);
        winnersRow.setEnabled(true);
        winnersRow.setEmailVerified(true);
        winnersRow.setRoles(Set.of("USER"));
        winnersRow.setUserVersion(1L);
        when(userRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty(), Optional.of(winnersRow));
        when(userRepository.save(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate provider_id"))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        // The winner already stamped the signup: the loser's claim is refused on the lifecycle worker.

        service.resolveUser(PROVIDER_ID, jwt(null, true));

        verify(lifecycleContext).recordSignup(USER_ID);
        verify(lifecycleEmails, never()).syncContactAndEmit(anyLong(), anyString(), anyMap());
        verify(lifecycleEmails, never()).emit(anyLong(), eq(LifecycleEvents.USER_SIGNED_UP), anyMap());
    }

    @Test
    @DisplayName("a failing signup guard never fails the login")
    void failingGuardNeverFailsResolve() {
        when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User created = inv.getArgument(0);
            created.setId(USER_ID);
            return created;
        });
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(lifecycleContext).recordSignup(USER_ID);

        org.assertj.core.api.Assertions.assertThat(service.resolveUser(PROVIDER_ID, jwt(null, true))).isNotNull();
    }

    @Test
    @DisplayName("a brand-new account with an unverified email sends nothing at creation (signed_up waits for verification)")
    void newUnverifiedAccountSendsNothing() {
        when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User created = inv.getArgument(0);
            created.setId(USER_ID);
            return created;
        });

        service.resolveUser(PROVIDER_ID, jwt(null, false));

        verify(lifecycleContext, never()).recordSignup(anyLong());
        verify(lifecycleEmails, never()).syncContactAndEmit(anyLong(), anyString(), anyMap());
        verify(lifecycleEmails, never()).emit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("an existing account never emits user.signed_up")
    void existingAccountNoSignup() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwt(Instant.now()));

        verify(lifecycleContext, never()).recordSignup(anyLong());
        verify(lifecycleEmails, never()).syncContactAndEmit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Regression (welcome lost): a real login of an existing account asks whether its welcome is still owed")
    void realLoginRetriesAnUnsentWelcome() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwt(Instant.now()));

        verify(lifecycleContext).retrySignupIfUnsent(user);
    }

    @Test
    @DisplayName("a token refresh (no newer authentication) never retries the welcome")
    void refreshDoesNotRetryTheWelcome() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(0);

        service.resolveUser(PROVIDER_ID, jwt(Instant.now()));

        verify(lifecycleContext, never()).retrySignupIfUnsent(any());
    }

    @Test
    @DisplayName("a failing welcome retry never fails the login")
    void failingRetryNeverFailsResolve() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(lifecycleContext).retrySignupIfUnsent(any());

        org.assertj.core.api.Assertions.assertThat(service.resolveUser(PROVIDER_ID, jwt(Instant.now()))).isNotNull();
    }

    @Test
    @DisplayName("a real login exactly 30 days after the previous one emits user.returned with days_away")
    void thirtyDaysAwayEmits() {
        user.setLastAuthenticatedAt(AUTH_AT.minusDays(30));
        when(userRepository.recordAuthenticationIfNewer(USER_ID, AUTH_AT)).thenReturn(1);

        service.recordAuthenticationAtomic(user, AUTH_AT);

        verify(lifecycleEmails).emit(USER_ID, LifecycleEvents.USER_RETURNED, Map.of("days_away", 30L));
    }

    @Test
    @DisplayName("29 days and 23 hours away is not a return")
    void twentyNineDaysIsNotAReturn() {
        user.setLastAuthenticatedAt(AUTH_AT.minusDays(30).plusHours(1));
        when(userRepository.recordAuthenticationIfNewer(USER_ID, AUTH_AT)).thenReturn(1);

        service.recordAuthenticationAtomic(user, AUTH_AT);

        verify(lifecycleEmails, never()).emit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("a resolve that lost the atomic advance (rows == 0) emits nothing, even after a long absence")
    void noRowsNoReturn() {
        user.setLastAuthenticatedAt(AUTH_AT.minusDays(90));
        when(userRepository.recordAuthenticationIfNewer(USER_ID, AUTH_AT)).thenReturn(0);

        service.recordAuthenticationAtomic(user, AUTH_AT);

        verify(lifecycleEmails, never()).emit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("a first-ever recorded authentication has no previous instant and is not a return")
    void noPreviousNoReturn() {
        user.setLastAuthenticatedAt(null);
        when(userRepository.recordAuthenticationIfNewer(USER_ID, AUTH_AT)).thenReturn(1);

        service.recordAuthenticationAtomic(user, AUTH_AT);

        verify(lifecycleEmails, never()).emit(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("the absence is measured from the value read BEFORE the write, through a real resolve")
    void resolveMeasuresFromPreviousValue() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        user.setLastAuthenticatedAt(nowUtc.minusDays(45));
        when(userRepository.recordAuthenticationIfNewer(USER_ID, nowUtc)).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwt(now));

        verify(lifecycleEmails).emit(USER_ID, LifecycleEvents.USER_RETURNED, Map.of("days_away", 45L));
    }

    private String jwt(Instant authTime) {
        return jwt(authTime, false);
    }

    private String jwt(Instant authTime, boolean emailVerified) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(PROVIDER_ID)
                .claim("email", "tester@test.com")
                .claim("email_verified", emailVerified);
        if (authTime != null) {
            claims.claim("auth_time", Date.from(authTime));
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
