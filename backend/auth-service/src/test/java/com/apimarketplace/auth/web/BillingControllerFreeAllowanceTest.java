package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.PlanStorageQuotaSyncer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The SECOND path that creates a FREE subscription row (V494).
 *
 * <p>{@code FreeSubscriptionProvisioner} is the first, and it has its own tests. Both
 * must agree on the same thing: the row is born with an EMPTY AI allowance. The pot
 * buys real platform-key inference, so it is granted with the monthly credits, behind
 * the email-verification gate - seeding it here (or there) hands an unverified
 * throwaway signup 100 credits of inference on the spot. Which of the two paths
 * created the row is invisible to the user, so a rule enforced in only one of them is
 * not enforced at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BillingController.createFreeSubscription - the AI allowance")
class BillingControllerFreeAllowanceTest {

    @Mock private PlanRepository planRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;

    private BillingController controller;
    private User user;
    private Plan freePlan;

    @BeforeEach
    void setUp() throws Exception {
        controller = new BillingController();
        inject("planRepository", planRepository);
        inject("subscriptionRepository", subscriptionRepository);
        inject("billingCustomerRepository", billingCustomerRepository);
        inject("quotaSyncer", quotaSyncer);

        user = new User();
        user.setId(77L);

        freePlan = new Plan();
        freePlan.setId(1L);
        freePlan.setCode("FREE");
        freePlan.setName("Free");

        // findOrCreate, not findByUserId: the controller stopped doing its own find-then-save.
        // Left as findByUserId this stub goes dead, the mocked default method hands back null,
        // and both tests below quietly run with a null BillingCustomer instead of going red.
        when(billingCustomerRepository.findOrCreate(77L, "internal"))
                .thenReturn(new BillingCustomer(user, "internal"));
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Field injection, so there is no constructor to hand the mocks to. */
    private void inject(String name, Object value) throws Exception {
        Field f = BillingController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private Subscription created() throws Exception {
        Method m = BillingController.class.getDeclaredMethod("createFreeSubscription", User.class);
        m.setAccessible(true);
        m.invoke(controller, user);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        org.mockito.Mockito.verify(subscriptionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("creates the row with an EMPTY allowance even when the plan configures one")
    void doesNotSeedTheAllowance() throws Exception {
        freePlan.setIncludedAiCredits(100);

        assertThat(created().getAiRemainingCredits())
                .as("the pot is granted with the verified-email credits, not at row creation")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a plan with no allowance leaves the pot empty too")
    void planWithoutAllowanceSeedsNothing() throws Exception {
        freePlan.setIncludedAiCredits(null);

        assertThat(created().getAiRemainingCredits()).isEqualByComparingTo("0");
    }
}
