package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PaygTopupReconciler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lost-top-up recovery, end to end on a real PostgreSQL: the JSONB query that finds paid
 * PAYG sessions with no ledger row, the Stripe-customer attribution, and the grant landing in
 * the PAYG bucket with the session id as idempotence key.
 *
 * <p>The seeded event is the production shape of a Stripe {@code checkout.session.completed}
 * for a PAYG top-up whose nonce was encrypted with another replica's ephemeral key.
 */
@SpringBootTest
@DisplayName("PAYG top-up reconciliation (real Postgres, JSONB query)")
class PaygTopupReconciliationRegressionTest extends AuthPostgresIntegrationTest {

    @Autowired private PaygTopupReconciler reconciler;
    @Autowired private BillingEventRepository billingEventRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;

    private final ObjectMapper json = new ObjectMapper();

    private User user;
    private Subscription freeRow;

    @BeforeEach
    void seed() {
        ledgerRepository.deleteAll();
        billingEventRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        Plan free = new Plan("FREE", "Free", "free");
        free.setIncludedLlmTokens(1000L);
        free.setIncludedToolCredits(1000L);
        free = planRepository.save(free);

        user = new User();
        user.setEmail("topup-it@test.local");
        user.setUsername("topup-it@test.local");
        user = userRepository.save(user);

        BillingCustomer bc = new BillingCustomer(user, "internal");
        bc.setProviderCustomerId("cus_it_topup");
        billingCustomerRepository.save(bc);

        freeRow = new Subscription();
        freeRow.setBillingCustomer(bc);
        freeRow.setPlan(free);
        freeRow.setProvider("internal");
        freeRow.setStatus("active");
        freeRow.setCadence("monthly");
        freeRow.setCurrentPeriodStart(LocalDateTime.now().minusDays(8));
        freeRow.setCurrentPeriodEnd(LocalDateTime.now().plusDays(22));
        freeRow.setRemainingCredits(new BigDecimal("1000"));
        freeRow.setPaygRemainingCredits(BigDecimal.ZERO);
        freeRow.setCreatedAt(LocalDateTime.now().minusDays(8));
        freeRow.setUpdatedAt(LocalDateTime.now().minusDays(8));
        freeRow = subscriptionRepository.save(freeRow);
    }

    private BillingEvent storedCheckout(String eventId, String sessionId, String paymentStatus,
                                        String kind, String creditAmount, int ageMinutes) throws Exception {
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{"
                + "\"id\":\"" + sessionId + "\",\"object\":\"checkout.session\",\"mode\":\"payment\","
                + "\"customer\":\"cus_it_topup\",\"client_reference_id\":\"n_encrypted_by_another_pod=\","
                + "\"payment_status\":\"" + paymentStatus + "\","
                + "\"metadata\":{\"kind\":\"" + kind + "\",\"tier\":\"small\",\"credit_amount\":\"" + creditAmount + "\","
                + "\"nonce\":\"n_encrypted_by_another_pod=\"}}}}";
        BillingEvent event = billingEventRepository.save(
                new BillingEvent("stripe", eventId, "checkout.session.completed", json.readTree(payload)));
        // @PrePersist stamps now(); age it past MIN_AGE so the reconciler considers it settled.
        event.setReceivedAt(LocalDateTime.now().minusMinutes(ageMinutes));
        return billingEventRepository.saveAndFlush(event);
    }

    private BigDecimal paygBalance() {
        return subscriptionRepository.findById(freeRow.getId()).orElseThrow().getPaygRemainingCredits();
    }

    @Test
    @DisplayName("a paid top-up with an unreadable nonce is credited to the Stripe customer's user, in the PAYG bucket, once")
    void lostTopupIsRecoveredOnceViaCustomer() throws Exception {
        storedCheckout("evt_it_lost", "cs_it_lost", "paid", "payg_topup", "8000", 10);

        assertThat(reconciler.reconcileOnce()).as("first pass grants it").isEqualTo(1);

        List<CreditLedgerEntry> ledger = ledgerRepository.findAll().stream()
                .filter(e -> "cs_it_lost".equals(e.getSourceId())).toList();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getSourceType()).isEqualTo("PAYG_TOPUP");
        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("8000");
        assertThat(ledger.get(0).getUserId()).isEqualTo(user.getId());
        assertThat(paygBalance()).as("PAYG bucket, not the sub bucket").isEqualByComparingTo("8000");

        assertThat(reconciler.reconcileOnce()).as("second pass finds nothing: the session id is now in the ledger").isZero();
        assertThat(paygBalance()).isEqualByComparingTo("8000");
        assertThat(ledgerRepository.findAll().stream().filter(e -> "cs_it_lost".equals(e.getSourceId()))).hasSize(1);
    }

    @Test
    @DisplayName("unpaid sessions, non-top-up checkouts and events younger than MIN_AGE are not touched")
    void onlySettledPaidTopupsAreCandidates() throws Exception {
        storedCheckout("evt_it_unpaid", "cs_it_unpaid", "unpaid", "payg_topup", "8000", 10);
        storedCheckout("evt_it_sub", "cs_it_sub", "paid", "subscription", "8000", 10);
        storedCheckout("evt_it_fresh", "cs_it_fresh", "paid", "payg_topup", "8000", 0);

        assertThat(reconciler.reconcileOnce()).isZero();

        assertThat(ledgerRepository.findAll().stream().filter(e -> e.getSourceId() != null && e.getSourceId().startsWith("cs_it_"))).isEmpty();
        assertThat(paygBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("two lost top-ups of the same user are both recovered and add up")
    void twoLostTopupsAddUp() throws Exception {
        storedCheckout("evt_it_1", "cs_it_1", "paid", "payg_topup", "8000", 10);
        storedCheckout("evt_it_2", "cs_it_2", "paid", "payg_topup", "8000", 9);

        assertThat(reconciler.reconcileOnce()).isEqualTo(2);

        assertThat(paygBalance()).isEqualByComparingTo("16000");
    }
}
