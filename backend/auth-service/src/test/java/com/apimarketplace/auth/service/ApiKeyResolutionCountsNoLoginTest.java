package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.domain.ApiKey;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.ApiKeyRepository;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.apimarketplace.common.security.CredentialEncryptionService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An API key is a machine, and a machine does not sign in.
 *
 * <p>This is the "no more fake logins when it is really a workflow" case, end to end and
 * on purpose: a REAL {@link UserResolutionService} behind a real {@link ApiKeyService},
 * with only the repositories and the recorder mocked. Testing the two halves separately
 * would prove nothing, because each half looks correct on its own: {@code ApiKeyService}
 * merely passes a null token, and {@code UserResolutionService} merely declines to count
 * one. What matters is that the null actually travels, and only a wired chain shows that.
 *
 * <p>Before the fix this path published a {@code login.success} per key per ten minutes,
 * for as long as the automation kept calling, because resolution counted elapsed time
 * rather than an authentication.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("API-key resolution counts no login")
class ApiKeyResolutionCountsNoLoginTest {

    private static final Long USER_ID = 42L;
    private static final String PROVIDER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String PLAINTEXT_KEY = "lc_live_pretend_this_is_a_real_key";
    private static final String HMAC_HASH = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";

    @Mock private UserRepository userRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private GatewayCacheClient gatewayCacheClient;
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

    private ApiKeyService apiKeyService;
    private User owner;

    @BeforeEach
    void setUp() {
        UserResolutionService userResolutionService = new UserResolutionService(
                userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository,
                billingCustomerRepository, planRepository, creditAttributionService,
                new PlanStorageQuotaSyncer(null, null), freeSubscriptionProvisioner);
        ReflectionTestUtils.setField(userResolutionService, "self", userResolutionService);
        ReflectionTestUtils.setField(userResolutionService, "authEventRecorder", authEventRecorder);

        apiKeyService = new ApiKeyService(
                userRepository, apiKeyRepository, encryptionService,
                userResolutionService, gatewayCacheClient);

        owner = new User();
        owner.setId(USER_ID);
        owner.setProviderId(PROVIDER_ID);
        owner.setUsername("automation-owner");
        owner.setEmail("owner@test.com");
        owner.setAuthProvider(AuthProvider.KEYCLOAK);
        owner.setEnabled(true);
        owner.setRoles(Set.of("USER"));
        owner.setUserVersion(1L);
        // Idle for hours, so the old "has enough time passed" rule would fire.
        owner.setLastLoginAt(LocalDateTime.now().minusHours(6));

        lenient().when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
        lenient().when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(owner));
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.<Subscription>empty());
        lenient().when(onboardingService.needsOnboarding(anyString())).thenReturn(false);
        lenient().when(authEventRecorder.providerTag(any())).thenReturn("keycloak");
        // The last-seen throttle lets its write through, as it would in production.
        lenient().when(userRepository.updateLastLoginIfStale(anyLong(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("a legacy single key on auth.users resolves without publishing a login")
    void legacyKeyCountsNoLogin() {
        when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.of(owner));

        assertThat(apiKeyService.resolveByPlaintextKey(PLAINTEXT_KEY)).isNotNull();

        assertNoLoginWasPublished();
    }

    @Test
    @DisplayName("a named key in auth.api_keys resolves without publishing a login")
    void namedKeyCountsNoLogin() {
        when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
        ApiKey key = new ApiKey();
        key.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        key.setUserId(USER_ID);
        when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.of(key));

        assertThat(apiKeyService.resolveByPlaintextKey(PLAINTEXT_KEY)).isNotNull();

        assertNoLoginWasPublished();
    }

    @Test
    @DisplayName("repeated calls by the same automation still publish nothing")
    void repeatedCallsStayQuiet() {
        when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.of(owner));

        for (int i = 0; i < 5; i++) {
            apiKeyService.resolveByPlaintextKey(PLAINTEXT_KEY);
        }

        assertNoLoginWasPublished();
    }

    private void assertNoLoginWasPublished() {
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
        verify(authEventRecorder, never()).recordSignupAndLogin(anyLong(), anyString(), anyBoolean());
        verify(userRepository, never()).recordAuthenticationIfNewer(anyLong(), any());
        // A machine with no token is an expected shape, not a broken identity provider:
        // reporting it would make the missing-claim metric useless by burying its signal.
        verify(authEventRecorder, never()).recordAuthTimeClaimMissing(anyString(), anyString());
    }
}
