package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.SubscriptionService;
import com.stripe.StripeClient;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FREE -> paid Stripe provisioning against the real V423 index, on a real PostgreSQL.
 *
 * <p>What broke in production: V423 added {@code idx_subscription_one_active_per_customer}
 * (UNIQUE billing_customer_id WHERE status IN active/trialing) while
 * {@code SubscriptionService.onSubscriptionUpsert} still wrote the new Stripe row before
 * canceling the user's active FREE row. Postgres rejected the write ({@code 23505}), the
 * "idempotent UPDATE" fallback then ran in the aborted session ({@code AssertionFailure: null
 * identifier}) and the webhook ended in "Unable to handle the subscription conflict" on every
 * Stripe retry: the customer was charged and never provisioned.
 *
 * <p>Hibernate's schema export cannot express a partial index, so the exact V423 DDL is applied
 * here by hand: without it the pre-fix code passes (nothing rejects the second active row) and
 * this test would prove nothing.
 */
@SpringBootTest
@DisplayName("Stripe upsert with the V423 one-active-per-customer index (real Postgres)")
class StripeUpsertOneActivePerCustomerRegressionTest extends AuthPostgresIntegrationTest {

    @MockitoBean private StripeClient stripe;

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;
    @Autowired private BillingEventRepository billingEventRepository;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private BillingCustomer bc;
    private Plan team;
    private Subscription freeRow;

    @BeforeEach
    void seedFreeUserUnderTheV423Index() throws Exception {
        ledgerRepository.deleteAll();
        billingEventRepository.deleteAll();
        subscriptionRepository.deleteAll();
        billingCustomerRepository.deleteAll();
        userRepository.deleteAll();
        planRepository.deleteAll();

        // Verbatim V423 backstop (ddl-auto=create-drop rebuilt the table without it).
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_subscription_one_active_per_customer "
                + "ON auth.subscription (billing_customer_id) WHERE status IN ('active', 'trialing')");

        Plan free = new Plan("FREE", "Free", "free");
        free.setIncludedLlmTokens(1000L);
        free.setIncludedToolCredits(1000L);
        free = planRepository.save(free);
        team = new Plan("TEAM", "Team", "team");
        team.setIncludedLlmTokens(1000L);
        team.setIncludedToolCredits(5000L);
        team = planRepository.save(team);

        user = new User();
        user.setEmail("upsert-it@test.local");
        user.setUsername("upsert-it@test.local");
        user = userRepository.save(user);

        bc = new BillingCustomer(user, "internal");
        bc.setProviderCustomerId("cus_test_upsert");
        bc = billingCustomerRepository.save(bc);

        // The state every user is in before their first checkout: one active internal FREE row,
        // holding its init credits AND a paid PAYG top-up (both buckets must survive the upgrade).
        freeRow = new Subscription();
        freeRow.setBillingCustomer(bc);
        freeRow.setPlan(free);
        freeRow.setProvider("internal");
        freeRow.setStatus("active");
        freeRow.setCadence("monthly");
        freeRow.setCurrentPeriodStart(LocalDateTime.now().minusDays(8));
        freeRow.setCurrentPeriodEnd(LocalDateTime.now().plusDays(22));
        freeRow.setRemainingCredits(new BigDecimal("1000"));
        freeRow.setPaygRemainingCredits(new BigDecimal("8000"));
        freeRow.setCreatedAt(LocalDateTime.now().minusDays(8));
        freeRow.setUpdatedAt(LocalDateTime.now().minusDays(8));
        freeRow = subscriptionRepository.save(freeRow);

        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        lenient().when(stripeSub.getId()).thenReturn("sub_team_live");
        lenient().when(stripeSub.getCustomer()).thenReturn("cus_test_upsert");
        lenient().when(stripeSub.getCancelAtPeriodEnd()).thenReturn(false);
        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(item.getQuantity()).thenReturn(1L);
        SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
        lenient().when(items.getData()).thenReturn(List.of(item));
        lenient().when(stripeSub.getItems()).thenReturn(items);
        com.stripe.service.SubscriptionService stripeSubscriptions = mock(com.stripe.service.SubscriptionService.class);
        when(stripe.subscriptions()).thenReturn(stripeSubscriptions);
        when(stripeSubscriptions.retrieve(anyString(), any(), isNull())).thenReturn(stripeSub);
    }

    private void upsertTeam(String eventId) throws Exception {
        subscriptionService.onSubscriptionUpsert(eventId, "sub_team_live", "active", team.getId(), null,
                LocalDateTime.now(), LocalDateTime.now().plusYears(1), user.getId(), null, 100, null);
    }

    private List<Subscription> liveRows() {
        return subscriptionRepository.findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                user.getId(), List.of("active", "trialing"));
    }

    private void assertFreeRowRetired() {
        Subscription retiredFree = subscriptionRepository.findById(freeRow.getId()).orElseThrow();
        assertThat(retiredFree.getStatus()).isEqualTo("canceled");
        assertThat(retiredFree.getCancelAtPeriodEnd()).isTrue();
        assertThat(retiredFree.getRemainingCredits()).isEqualByComparingTo("0");
        assertThat(retiredFree.getPaygRemainingCredits()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("customer.subscription.created for a FREE user provisions the paid row instead of dying on the index")
    void freeToTeamProvisionsUnderTheIndex() throws Exception {
        // Pre-fix: RuntimeException("Unable to handle the subscription conflict").
        assertThatCode(() -> upsertTeam("evt_team_1")).doesNotThrowAnyException();

        List<Subscription> live = liveRows();
        assertThat(live).as("exactly one active row, the paid one").hasSize(1);
        Subscription teamRow = live.get(0);
        assertThat(teamRow.getProvider()).isEqualTo("stripe");
        assertThat(teamRow.getProviderSubscriptionId()).isEqualTo("sub_team_live");
        // Plan is lazy: compare ids, the test runs outside a persistence context.
        assertThat(teamRow.getPlan().getId()).isEqualTo(team.getId());
        assertThat(teamRow.getCreditQuantity()).isEqualTo(100);
        // 1000 carried over from the FREE row + the 100 000-credit tier-4 pack the customer paid for.
        assertThat(teamRow.getRemainingCredits()).isEqualByComparingTo("101000");
        // The PAYG top-up travels in its own bucket (V250): never folded into sub credits.
        assertThat(teamRow.getPaygRemainingCredits()).isEqualByComparingTo("8000");

        assertFreeRowRetired();
    }

    @Test
    @DisplayName("an existing Stripe row going incomplete -> active (async payment) is re-activated under the index, FREE retired")
    void managedRowIncompleteToActiveUnderTheIndex() throws Exception {
        // Checkout already created the local row (Stripe status incomplete) while the FREE row is
        // still the active one; the payment confirmation then flips it to active. This is the path
        // where the FLUSH of the sibling, not the insert order, is what keeps the index satisfied.
        Subscription pending = new Subscription();
        pending.setBillingCustomer(bc);
        pending.setPlan(team);
        pending.setProvider("stripe");
        pending.setProviderSubscriptionId("sub_team_live");
        pending.setStatus("incomplete");
        pending.setCadence("monthly");
        pending.setCreditQuantity(100);
        pending.setCurrentPeriodStart(LocalDateTime.now());
        pending.setCurrentPeriodEnd(LocalDateTime.now().plusYears(1));
        pending.setCreatedAt(LocalDateTime.now());
        pending.setUpdatedAt(LocalDateTime.now());
        pending = subscriptionRepository.save(pending);

        assertThatCode(() -> upsertTeam("evt_team_activated")).doesNotThrowAnyException();

        List<Subscription> live = liveRows();
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getId()).as("the pending row itself was re-activated, no new row").isEqualTo(pending.getId());
        assertThat(live.get(0).getStatus()).isEqualTo("active");
        // Same plan and pack as before: no new grant, only the FREE balances carried over.
        assertThat(live.get(0).getRemainingCredits()).isEqualByComparingTo("1000");
        assertThat(live.get(0).getPaygRemainingCredits()).isEqualByComparingTo("8000");
        assertThat(subscriptionRepository.findAll()).hasSize(2);

        assertFreeRowRetired();
    }

    @Test
    @DisplayName("a Stripe retry of the same subscription event is idempotent: still one active row, no duplicate insert")
    void stripeRetryIsIdempotent() throws Exception {
        upsertTeam("evt_team_1");

        assertThatCode(() -> upsertTeam("evt_team_1_retry")).doesNotThrowAnyException();

        List<Subscription> live = liveRows();
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getProviderSubscriptionId()).isEqualTo("sub_team_live");
        assertThat(live.get(0).getRemainingCredits()).as("no second grant, no second carry-over").isEqualByComparingTo("101000");
        assertThat(live.get(0).getPaygRemainingCredits()).isEqualByComparingTo("8000");
        assertThat(subscriptionRepository.findAll()).as("FREE (canceled) + TEAM, nothing else").hasSize(2);
    }
}
