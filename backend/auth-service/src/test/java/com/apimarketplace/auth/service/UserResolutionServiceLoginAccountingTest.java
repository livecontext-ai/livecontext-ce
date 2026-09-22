package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserResolutionResponse;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A login is an AUTHENTICATION, not a request.
 *
 * <p>Regression suite for the accounting bug measured on prod 2026-09-17: resolveUser
 * treated "last_login_at was more than 10 minutes old" as "this person just signed in",
 * and resolveUser runs on every gateway request carrying a JWT. Result: 96 login.success
 * in 12 hours for 3 accounts, smallest gap between two events for one account exactly
 * 10.0 minutes, against 16 real LOGIN events in Keycloak over the same 24 hours.
 *
 * <p>Every test here fails on the pre-fix code, except the one asserting the "last seen"
 * write still happens, which is there to prove the fix did not trade one bug for another.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService - what counts as a login")
class UserResolutionServiceLoginAccountingTest {

    private static final String PROVIDER_ID = "kc-sub-1234";
    private static final long USER_ID = 42L;
    /** Must equal what JwtTokenProvider stamps (auth.jwt.issuer, default "livecontext"). */
    private static final String EMBEDDED_ISSUER = "livecontext";

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
        ReflectionTestUtils.setField(service, "embeddedJwtIssuer", EMBEDDED_ISSUER);

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
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.<Subscription>empty());
        lenient().when(onboardingService.needsOnboarding(anyString())).thenReturn(false);
        lenient().when(authEventRecorder.providerTag(any())).thenReturn("keycloak");
        // The "last seen" throttle lets a write through. Under the old rule that alone
        // published a login, which is exactly what must no longer happen.
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("a refreshed token presents its session's ORIGINAL auth_time, unchanged")
    void refreshedTokenPresentsTheSameInstant() {
        // The property that makes a refresh free: Keycloak reissues the access token with a
        // new iat but the SAME auth_time, verified against Keycloak 26.6.1 (see
        // the project docs). What has to hold on OUR side is that the value handed
        // to the repository comes from the token and not from the clock, because the
        // repository's `<` is what turns "same instant" into "no login". Asserting only the
        // stubbed rowcount would leave an implementation that passes now() looking correct.
        Instant sessionStart = Instant.now().minusSeconds(7200).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime expected = LocalDateTime.ofInstant(sessionStart, ZoneOffset.UTC);
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(0);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(sessionStart));
        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(sessionStart));

        verify(userRepository, times(2)).recordAuthenticationIfNewer(USER_ID, expected);
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        verify(authEventRecorder, never()).recordSignupAndLogin(anyLong(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("the 10-minute staleness throttle alone no longer publishes a login")
    void staleLastSeenAloneIsNotALogin() {
        // THE regression. Pre-fix, updateLastLoginIfStale returning 1 WAS the login signal,
        // so this exact call emitted login.success: once per active principal per 10 min,
        // forever, for an open tab, a scheduled workflow or an API key.
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(0);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now().minusSeconds(86400)));

        verify(userRepository).updateLastLoginIfStale(eq(USER_ID), any(), any());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("a newer auth_time is a login, and is counted exactly once")
    void newerAuthTimeIsALogin() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now()));

        verify(authEventRecorder, times(1)).recordLoginSuccess(USER_ID, "keycloak");
    }

    @Test
    @DisplayName("the instant handed to the repository is the token auth_time, never now()")
    void advancesToTheTokenAuthTime() {
        Instant authenticatedAt = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(authenticatedAt));

        // Passing now() instead would advance the column on every request and make the
        // comparison vacuous: every later token would look older and never count again.
        LocalDateTime expected = LocalDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC);
        verify(userRepository).recordAuthenticationIfNewer(USER_ID, expected);
    }

    @Test
    @DisplayName("no token at all (API key, internal caller) is silent: no login, no warning metric")
    void noTokenIsSilentlyNonInteractive() {
        service.resolveUser(PROVIDER_ID, null);

        verify(userRepository, never()).recordAuthenticationIfNewer(anyLong(), any());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        // Silence matters as much as the absent login: an API key legitimately has no
        // auth_time, so reporting it as a missing claim would make the metric that
        // detects a broken identity provider fire constantly and mean nothing.
        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(anyString(), anyString());
    }

    @Test
    @DisplayName("a trusted token WITHOUT auth_time counts no login but is reported loudly")
    void tokenWithoutAuthTimeIsReported() {
        service.resolveUser(PROVIDER_ID, jwtWithoutAuthTime());

        verify(userRepository, never()).recordAuthenticationIfNewer(anyLong(), any());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        // Without this, an identity provider that stopped sending the claim would look
        // exactly like a day on which nobody signed in.
        verify(authEventRecorder).recordAuthTimeClaimMissing("keycloak", "absent");
    }

    @Test
    @DisplayName("the missing-claim counter increments on every request, so its rate is usable")
    void missingClaimIsCountedEveryTime() {
        service.resolveUser(PROVIDER_ID, jwtWithoutAuthTime());
        service.resolveUser(PROVIDER_ID, jwtWithoutAuthTime());

        // The log line is throttled to once per provider; the COUNTER must not be, or
        // rate() over it would read flat zero after the first occurrence.
        verify(authEventRecorder, times(2)).recordAuthTimeClaimMissing("keycloak", "absent");
    }

    @Test
    @DisplayName("last seen is still written even when nothing counted as a login")
    void lastSeenStillWritten() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(0);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now().minusSeconds(600)));

        // last_login_at feeds the account-deletion grace window and the "last seen"
        // display. Decoupling it from the login count must not stop it being written.
        verify(userRepository).updateLastLoginIfStale(eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("a storage failure costs a login count, never the resolution itself")
    void advanceFailureFailsClosed() {
        // Thrown from the repository, so it propagates OUT of recordAuthenticationAtomic and
        // has to be caught on the far side of the transaction boundary. That placement is the
        // point: catching inside the @Transactional method would not contain it, because the
        // proxy still throws UnexpectedRollbackException at commit, after the method returns.
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any()))
                .thenThrow(new RuntimeException("connection reset"));

        UserResolutionResponse response = service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now()));

        // The resolve itself must survive, since a broken counter cannot be allowed to log
        // anybody out, and it must not invent a sign-in on the audit trail either.
        assertThat(response).isNotNull();
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("authenticationInstant reads auth_time to the second")
    void authenticationInstantReadsTheClaim() {
        Instant authenticatedAt = Instant.ofEpochSecond(1_700_000_000L);

        LocalDateTime read = service.authenticationInstant(jwtWithAuthTime(authenticatedAt), user);

        assertThat(read).isEqualTo(LocalDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an unparseable token is reported, not swallowed")
    void unparseableTokenIsReported() {
        // It must not throw: the gateway already validated the signature to get this far,
        // so a token we cannot read here is our problem, not a reason to refuse the request.
        assertThat(service.authenticationInstant("not-a-jwt", user)).isNull();

        // And it must be visible. Moving the report into the "claim genuinely absent" branch
        // would make a malformed token vanish without a trace, which is the same blindness
        // the counter exists to prevent.
        verify(authEventRecorder).recordAuthTimeClaimMissing(anyString(), anyString());
    }

    @Test
    @DisplayName("a self-hosted embedded token is silent: not a login, not a broken provider")
    void embeddedTokenIsSilentlyNonInteractive() {
        service.resolveUser(PROVIDER_ID, embeddedJwt("local"));

        verify(userRepository, never()).recordAuthenticationIfNewer(anyLong(), any());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        // Our own JwtTokenProvider has no auth_time to give, so reporting it would page a
        // self-hosted install about a provider it does not run. It would be mislabelled too:
        // the tag is derived from the token and can only ever say keycloak/google/github.
        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(anyString(), anyString());
    }

    @Test
    @DisplayName("an embedded token stays silent whichever sign-in method it names")
    void embeddedTokenIsSilentForEverySignInMethod() {
        // The trap this pins: `provider` carries the SIGN-IN METHOD, not who minted the
        // token. A self-hosted person who used Google carries provider="google" on a token
        // WE signed, so a guard keyed on "local" would let it through and the install would
        // be told that "keycloak" had stopped sending auth_time, running no Keycloak at all.
        service.resolveUser(PROVIDER_ID, embeddedJwt("google"));
        service.resolveUser(PROVIDER_ID, embeddedJwt("github"));

        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(anyString(), anyString());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("a foreign token carrying token_type is not mistaken for one of ours")
    void foreignTokenWithTokenTypeIsNotOurs() {
        String foreign = sign(new JWTClaimsSet.Builder()
                .subject(PROVIDER_ID)
                .issuer("https://someone-else.example.com/realms/other")
                .claim("email", "tester@test.com")
                .claim("token_type", "access"));

        service.resolveUser(PROVIDER_ID, foreign);

        // Misclassifying a token as ours short-circuits with NO login, NO counter and NO log
        // line, which is the one branch of this design with no detector. Pinning the issuer
        // as well as the claim is what keeps that branch narrow.
        verify(authEventRecorder).recordAuthTimeClaimMissing("keycloak", "absent");
    }

    @Test
    @DisplayName("the missing-claim report names the provider that actually sent the token")
    void missingClaimNamesTheRealProvider() {
        service.resolveUser(PROVIDER_ID, brokeredJwtWithoutAuthTime("google"));

        // AuthTimeClaimMissing pages with the provider name. Deriving it from the USER row
        // instead of the token would blame whichever provider that account signed up with,
        // which on a brokered login is not the one that just failed to send the claim.
        verify(authEventRecorder).recordAuthTimeClaimMissing("google", "absent");
        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(eq("keycloak"), anyString());
    }

    @Test
    @DisplayName("an auth_time far in the future is refused, not stored")
    void futureDatedAuthTimeIsRefused() {
        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now().plusSeconds(7 * 24 * 3600)));

        // Storing it would win every future comparison, so that account's logins would go
        // uncounted until the wall clock caught up, with no way back short of a DB edit.
        // Monotonicity is what makes two devices safe AND what makes a skew unrecoverable.
        verify(userRepository, never()).recordAuthenticationIfNewer(anyLong(), any());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        // Reported under its OWN reason. Filing it as "absent" would page an operator with
        // "check whether the provider emits auth_time" when the claim is there and the
        // provider's CLOCK is the fault: a correct alert carrying the wrong diagnosis.
        verify(authEventRecorder).recordAuthTimeClaimMissing("keycloak", "future");
        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(anyString(), eq("absent"));
    }

    @Test
    @DisplayName("a small clock drift forward is still accepted")
    void smallForwardDriftIsAccepted() {
        when(userRepository.recordAuthenticationIfNewer(eq(USER_ID), any())).thenReturn(1);

        service.resolveUser(PROVIDER_ID, jwtWithAuthTime(Instant.now().plusSeconds(30)));

        // The bound has to tolerate ordinary NTP drift between two hosts, or a healthy
        // provider would be reported as broken and real sign-ins would stop counting.
        verify(authEventRecorder).recordLoginSuccess(USER_ID, "keycloak");
    }

    @Test
    @DisplayName("the instant is built in UTC, so the DST repeated hour cannot swallow a sign-in")
    void instantIsBuiltInUtc() {
        Instant authenticatedAt = Instant.ofEpochSecond(1_700_000_000L);

        LocalDateTime read = service.authenticationInstant(jwtWithAuthTime(authenticatedAt), user);

        // Asserted against a FIXED zone rather than against the same expression production
        // uses. With the JVM zone, the hour that repeats at the end of DST maps two distinct
        // instants to one LocalDateTime, and a genuinely newer authentication inside it would
        // not compare greater and would be dropped in silence, once a year.
        assertThat(read).isEqualTo(LocalDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a brand-new account records a signup AND its first login")
    void firstEverResolutionRecordsSignupAndLogin() {
        // The isNewUser disjunct: it is the only thing that records anything when the
        // creating token has no auth_time to compare against, and nothing exercised it.
        when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User created = inv.getArgument(0);
            created.setId(USER_ID);
            return created;
        });
        service.resolveUser(PROVIDER_ID, jwtWithoutAuthTime());

        // Deliberately a token with NO auth_time, which is the case the disjunct exists for:
        // with nothing to compare against, isNewUser is the only thing that can record the
        // first sign-in. Handing it a valid auth_time would let the other disjunct carry the
        // test and prove nothing about this one.
        verify(authEventRecorder, times(1)).recordSignupAndLogin(eq(USER_ID), anyString(), anyBoolean());
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    private String jwtWithAuthTime(Instant authenticatedAt) {
        return sign(new JWTClaimsSet.Builder()
                .subject(PROVIDER_ID)
                .claim("email", "tester@test.com")
                .claim("auth_time", Date.from(authenticatedAt.truncatedTo(ChronoUnit.SECONDS))));
    }

    /**
     * What JwtTokenProvider mints on a self-hosted install: no auth_time, a token_type, and
     * a provider claim naming the SIGN-IN METHOD the person used.
     */
    private String embeddedJwt(String provider) {
        return sign(new JWTClaimsSet.Builder()
                .subject(String.valueOf(USER_ID))
                .issuer(EMBEDDED_ISSUER)
                .claim("email", "tester@test.com")
                .claim("token_type", "access")
                .claim("provider", provider));
    }

    /** A Keycloak token brokered from an upstream provider, with no auth_time. */
    private String brokeredJwtWithoutAuthTime(String identityProvider) {
        return sign(new JWTClaimsSet.Builder()
                .subject(PROVIDER_ID)
                .claim("email", "tester@test.com")
                .claim("identity_provider", identityProvider));
    }

    private String jwtWithoutAuthTime() {
        return sign(new JWTClaimsSet.Builder()
                .subject(PROVIDER_ID)
                .claim("email", "tester@test.com"));
    }

    private String sign(JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner("super-secret-key-that-is-at-least-32-bytes-long!!"));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test JWT", e);
        }
    }
}
