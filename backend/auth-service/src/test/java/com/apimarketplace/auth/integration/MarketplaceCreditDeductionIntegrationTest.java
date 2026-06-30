package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates the cloud-authoritative credit deduction for a paid marketplace
 * acquire against a real DB (the "install d'app déduit les crédits" + "pas de
 * dépassement négatif" checks). No paid publication exists on prod and a real
 * acquire would spend real credits - so this exercises the exact server-side
 * entry point {@link CreditService#consumeForMarketplacePurchase} with a seeded
 * balance. {@code credit.unlimited=false} so deduction actually happens (CE's
 * unlimited mode is local-only and would short-circuit the wallet).
 */
@IntegrationTest
@TestPropertySource(properties = "credit.unlimited=false")
@DisplayName("Marketplace credit deduction (real DB)")
class MarketplaceCreditDeductionIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private CreditService creditService;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = new User("buyer", "buyer@example.com", AuthProvider.KEYCLOAK,
                "f47ac10b-58cc-4372-a567-0e02b2c3d481");
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setUserVersion(1L);
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        Plan plan = planRepository.saveAndFlush(new Plan("STARTER", "Starter", "Starter paid plan"));
        BillingCustomer customer = billingCustomerRepository.saveAndFlush(new BillingCustomer(user, "internal"));

        Subscription sub = new Subscription();
        sub.setBillingCustomer(customer);
        sub.setPlan(plan);
        sub.setStatus("active");
        sub.setCadence("monthly");
        sub.setProvider("internal");
        sub.setQuantity(1);
        sub.setRemainingCredits(new BigDecimal("100"));
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(1));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(29));
        sub.setCancelAtPeriodEnd(false);
        subscriptionRepository.saveAndFlush(sub);
    }

    @Test
    @DisplayName("a paid acquire debits the wallet by the publication price")
    void paidAcquireDebitsWallet() {
        assertThat(creditService.getBalance(userId)).isEqualByComparingTo("100");

        creditService.consumeForMarketplacePurchase(userId, "pub-paid-1", 30);

        assertThat(creditService.getBalance(userId)).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("insufficient balance → acquire rejected, wallet NOT debited (no negative bypass)")
    void insufficientBalanceRejectedWithoutDebit() {
        // 70 left after the (rolled-back-per-test) baseline is 100 here; ask for more than the balance.
        creditService.consumeForMarketplacePurchase(userId, "pub-paid-1", 30); // 100 -> 70
        assertThat(creditService.getBalance(userId)).isEqualByComparingTo("70");

        creditService.consumeForMarketplacePurchase(userId, "pub-too-expensive", 1000); // 70 < 1000

        // Balance unchanged - the deduction was refused, no negative balance.
        assertThat(creditService.getBalance(userId)).isEqualByComparingTo("70");
    }
}
