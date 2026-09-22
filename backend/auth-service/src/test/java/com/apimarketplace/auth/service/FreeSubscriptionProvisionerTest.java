package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Provisioning of the bootstrap FREE subscription.
 *
 * <p>The defect this pins: the previous implementation read "is there an active subscription?"
 * and inserted one if not, with no lock and no constraint in between, from a code path that runs
 * on every gateway request. Concurrent first-login requests each created one. Production carries
 * three users with two active FREE subscriptions created 7-15 ms apart, and only one row of each
 * pair was ever funded.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FreeSubscriptionProvisioner Tests")
class FreeSubscriptionProvisionerTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;

    @InjectMocks private FreeSubscriptionProvisioner provisioner;

    private static final Long USER_ID = 77L;

    private User user;
    private BillingCustomer billingCustomer;
    private Plan freePlan;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        billingCustomer = new BillingCustomer(user, "internal");
        billingCustomer.setId(9L);
        freePlan = new Plan();
        freePlan.setId(1L);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        freePlan.setIncludedLlmTokens(1000L);
    }

    private void givenLockedBillingCustomer() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(billingCustomer));
    }

    @Test
    @DisplayName("creates the FREE subscription when the user has none")
    void createsWhenMissing() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            s.setId(500L);
            return s;
        });

        assertThat(provisioner.provisionIfMissing(user)).contains(500L);
        verify(quotaSyncer).syncAfterCommit(eq(USER_ID), eq(freePlan));
    }

    @Test
    @DisplayName("the DECIDING check is the one taken under the per-user lock")
    void theDecidingCheckIsTakenUnderTheLock() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> withId(inv.getArgument(0), 503L));

        provisioner.provisionIfMissing(user);

        // The DECIDING check must sit under the lock. There is a cheap one before it too, which
        // spares the warm path a row lock on every gateway request, but that one decides nothing:
        // on its own, check-then-lock leaves exactly the race that created the duplicate
        // subscriptions, because two requests both read "none" before either inserts. So assert
        // the full shape: check, lock, check AGAIN, then insert.
        InOrder order = inOrder(billingCustomerRepository, subscriptionRepository);
        order.verify(subscriptionRepository).findActiveByUserId(USER_ID);
        order.verify(billingCustomerRepository).findByUserIdForUpdate(USER_ID);
        order.verify(subscriptionRepository).findActiveByUserId(USER_ID);
        order.verify(subscriptionRepository).save(any(Subscription.class));
        verify(subscriptionRepository, times(2)).findActiveByUserId(USER_ID);
    }

    @Test
    @DisplayName("an already-provisioned user is answered without taking the mutex at all")
    void earlyOutNeverTouchesTheBillingCustomer() {
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(new Subscription()));

        assertThat(provisioner.provisionIfMissing(user)).isEmpty();

        // This is the answer on virtually every call, since resolveUser runs on every gateway
        // request. Without it, every one of those requests took a PESSIMISTIC_WRITE lock on the
        // user's billing-customer row just to be told there was nothing to do.
        verifyNoInteractions(billingCustomerRepository);
    }

    @Test
    @DisplayName("a subscription that appeared WHILE we waited on the mutex is honoured, not duplicated")
    void theInLockRecheckStopsTheRacerThatWaited() {
        // The reason the mutex exists. This racer read "none" before the lock, then blocked on
        // the winner, and by the time it holds the lock the winner has committed a subscription.
        // Only the second check can see that; skip it and this thread inserts the duplicate the
        // whole design is there to prevent. The cheap early-out cannot cover this case, which is
        // why it is not a replacement for the check under the lock.
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.empty())                  // before the lock: nothing yet
                .thenReturn(Optional.of(new Subscription()));   // under the lock: the winner's

        assertThat(provisioner.provisionIfMissing(user)).isEmpty();

        verify(subscriptionRepository, times(2)).findActiveByUserId(USER_ID);
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(planRepository, never()).findByCode(any());
    }

    @Test
    @DisplayName("the subscription backstop firing propagates, because only the caller can absorb it")
    void uniqueConstraintRejectionPropagatesToTheCaller() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenThrow(new DataIntegrityViolationException("idx_subscription_one_active_per_customer"));

        // This used to be caught here and reported as a tidy empty Optional. That reads as the
        // kind thing to do and it is a lie: the transaction is already doomed, so the commit
        // throws an UnexpectedRollbackException no catch inside this bean can intercept, and
        // the caller receives that instead of the empty Optional it was promised. Letting it
        // out is honest, and UserResolutionService.ensureFreeSubscription, which sits outside
        // this transaction, is the one place able to decide it costs nobody their login.
        assertThatThrownBy(() -> provisioner.provisionIfMissing(user))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(quotaSyncer, never()).syncAfterCommit(anyLong(), any(Plan.class));
    }

    @Test
    @DisplayName("creates nothing when the FREE plan is missing from the catalog")
    void noopWhenFreePlanMissing() {
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.empty());

        assertThat(provisioner.provisionIfMissing(user)).isEmpty();
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    @DisplayName("a user with no billing customer gets one from an insert that CANNOT raise, then is provisioned")
    void createsBillingCustomerWhenAbsent() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.empty())              // nothing yet
                .thenReturn(Optional.of(billingCustomer)); // ours, or the winner of the race
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> withId(inv.getArgument(0), 501L));

        assertThat(provisioner.provisionIfMissing(user)).contains(501L);

        // The mutex contract, in order: look, insert-if-absent, look again under the lock. The
        // second read is what makes the insert a mutex rather than a hopeful write, so pin the
        // sequence and not merely the calls.
        InOrder order = inOrder(billingCustomerRepository);
        order.verify(billingCustomerRepository).findByUserIdForUpdate(USER_ID);
        order.verify(billingCustomerRepository).insertIfAbsent(USER_ID, "internal");
        order.verify(billingCustomerRepository).findByUserIdForUpdate(USER_ID);

        // save() here used to be wrapped in a catch that re-read the winner's row on a unique
        // violation. That cannot work, and it is not a matter of taste: the violation has
        // already aborted the PostgreSQL transaction and flagged it rollback-only, so the
        // re-read fails and the commit throws an UnexpectedRollbackException past every catch
        // in the class. ON CONFLICT DO NOTHING raises nothing, which is the only version of
        // this that holds under the concurrency the path actually sees.
        verify(billingCustomerRepository, never()).save(any(BillingCustomer.class));
    }

    @Test
    @DisplayName("a billing customer still absent after the insert fails loudly, it does not guess")
    void missingBillingCustomerAfterInsertIsNotSwallowed() {
        when(billingCustomerRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        // Impossible unless the insert silently did nothing AND no row exists, which would mean
        // the mutex is not a mutex. Better a named failure the caller logs than a subscription
        // attached to a billing customer nobody locked.
        assertThatThrownBy(() -> provisioner.provisionIfMissing(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billing customer still absent");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    /** A persisted subscription always comes back with an id; the fixtures must say so. */
    private static Subscription withId(Subscription sub, Long id) {
        sub.setId(id);
        return sub;
    }

    @Test
    @DisplayName("a user with no id is refused without touching the database")
    void refusesUnsavedUser() {
        assertThat(provisioner.provisionIfMissing(new User())).isEmpty();
        assertThat(provisioner.provisionIfMissing(null)).isEmpty();
        verifyNoInteractions(billingCustomerRepository, subscriptionRepository, planRepository);
    }

    @Test
    @DisplayName("V494: the row is created with an EMPTY AI allowance, even when the plan configures one")
    void doesNotSeedTheAiAllowanceAtSignup() {
        // The allowance buys real platform-key inference, so it is granted where the
        // monthly credits are granted: behind the email-verification gate, by
        // CreditAttributionService. Seeding it on the row at creation reads as
        // harmless and is not - it hands every unverified throwaway signup 100
        // credits of inference, immediately and scriptably, while the 1000 monthly
        // credits sitting beside it stay correctly withheld.
        freePlan.setIncludedAiCredits(100);
        givenLockedBillingCustomer();
        when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        when(subscriptionRepository.save(saved.capture())).thenAnswer(inv -> withId(inv.getArgument(0), 600L));

        provisioner.provisionIfMissing(user);

        assertThat(saved.getValue().getAiRemainingCredits())
                .as("the pot arrives with the verified-email credit grant, not with the row")
                .isEqualByComparingTo("0");
    }

}
