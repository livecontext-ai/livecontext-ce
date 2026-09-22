package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code attributeCreditsIfEligible} runs on every gateway request of a FREE user. The grant
 * itself is idempotent, but each call used to re-read the active subscription, re-check the
 * ledger and log "Attributing credits for new subscription" (75 lines for one user in two short
 * sessions, 2026-09-15). The memo keyed by subscription id makes the second and later requests
 * free, without changing when a grant can happen.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FREE credit attribution runs once per subscription per JVM, not once per request")
class UserResolutionServiceFreeCreditMemoTest {

    @Mock private UserRepository userRepository;
    @Mock private CreditService creditService;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private FreeSubscriptionProvisioner freeSubscriptionProvisioner;

    private UserResolutionService service;
    private User verifiedUser;

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService, subscriptionRepository, billingCustomerRepository,
                planRepository, creditAttributionService, new PlanStorageQuotaSyncer(null, null),
                freeSubscriptionProvisioner);
        verifiedUser = new User();
        verifiedUser.setId(121L);
        verifiedUser.setEmail("u121@test.local");
        verifiedUser.setEmailVerified(true);
    }

    private Subscription freeSub(long id) {
        Plan free = new Plan("FREE", "Free", "free");
        free.setId(20L);
        Subscription s = new Subscription();
        s.setId(id);
        s.setPlan(free);
        s.setProvider("internal");
        s.setStatus("active");
        return s;
    }

    @Test
    @DisplayName("the first request attributes, the following requests of the same subscription touch nothing")
    void secondRequestIsANoOp() {
        Subscription sub = freeSub(70L);
        when(subscriptionRepository.findActiveByUserId(121L)).thenReturn(Optional.of(sub));

        service.attributeCreditsIfEligible(verifiedUser);
        service.attributeCreditsIfEligible(verifiedUser);
        service.attributeCreditsIfEligible(verifiedUser);

        // Pre-fix: three attribution calls (three ledger reads, three INFO lines).
        verify(creditAttributionService, times(1)).attributeOnSubscription(eq(121L), eq(sub), eq(0));
    }

    @Test
    @DisplayName("a new FREE subscription id (re-provisioned after a downgrade) is attributed on its own")
    void newSubscriptionIdIsAttributedAgain() {
        Subscription first = freeSub(70L);
        Subscription second = freeSub(85L);
        when(subscriptionRepository.findActiveByUserId(121L))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));

        service.attributeCreditsIfEligible(verifiedUser);
        service.attributeCreditsIfEligible(verifiedUser);
        service.attributeCreditsIfEligible(verifiedUser);

        verify(creditAttributionService, times(1)).attributeOnSubscription(eq(121L), eq(first), eq(0));
        verify(creditAttributionService, times(1)).attributeOnSubscription(eq(121L), eq(second), eq(0));
    }

    @Test
    @DisplayName("a failed attribution is not memoized: the next request retries, exactly as before")
    void failureIsRetriedOnTheNextRequest() {
        Subscription sub = freeSub(70L);
        when(subscriptionRepository.findActiveByUserId(121L)).thenReturn(Optional.of(sub));
        doThrow(new IllegalStateException("db hiccup"))
                .doNothing()
                .when(creditAttributionService).attributeOnSubscription(eq(121L), eq(sub), eq(0));

        // The failure now leaves this method instead of being swallowed inside it. That is the
        // point: the method is @Transactional, so a catch in here could not have saved the
        // transaction anyway, only hidden the failure until the commit threw where no catch of
        // its own could see it. Both callers hold the catch on their side of the proxy now
        // (attributeCreditsSafely, and the email-verification controller). What matters to THIS
        // test is unchanged and still asserted below: a failed grant is not memoized, so the
        // next request tries again.
        assertThatThrownBy(() -> service.attributeCreditsIfEligible(verifiedUser))
                .isInstanceOf(IllegalStateException.class);
        service.attributeCreditsIfEligible(verifiedUser);   // retried, succeeds, memoized
        service.attributeCreditsIfEligible(verifiedUser);   // no-op

        verify(creditAttributionService, times(2)).attributeOnSubscription(eq(121L), eq(sub), eq(0));
    }

    @Test
    @DisplayName("an unverified email or a paid (stripe) subscription is never attributed here, memo or not")
    void ineligibleUsersAreUntouched() {
        User unverified = new User();
        unverified.setId(5L);
        unverified.setEmailVerified(false);
        service.attributeCreditsIfEligible(unverified);

        Subscription stripeSub = freeSub(84L);
        stripeSub.setProvider("stripe");
        when(subscriptionRepository.findActiveByUserId(121L)).thenReturn(Optional.of(stripeSub));
        service.attributeCreditsIfEligible(verifiedUser);
        service.attributeCreditsIfEligible(verifiedUser);

        verify(creditAttributionService, never()).attributeOnSubscription(any(), any(), anyInt());
        verify(subscriptionRepository, never()).findActiveByUserId(5L);
    }
}
