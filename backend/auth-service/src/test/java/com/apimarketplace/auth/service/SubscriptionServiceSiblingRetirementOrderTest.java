package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the FREE -> paid provisioning failure introduced by V423.
 *
 * <p>V423 added the partial unique index "one active/trialing subscription per billing
 * customer". {@code onSubscriptionUpsert} kept inserting the new Stripe row FIRST and only
 * canceled the user's FREE row afterwards, so from V423 on the insert was rejected by the
 * index, the "idempotent UPDATE" fallback ran in an already-aborted session and every paid
 * checkout ended in "Unable to handle the subscription conflict": charged, never provisioned.
 *
 * <p>The mock-level contract pinned here: the sibling must be retired with a FLUSH before the
 * new row is saved, and both credit buckets must travel with it. The real-database proof
 * (with the actual index) lives in {@code StripeUpsertOneActivePerCustomerRegressionTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService retires the active sibling BEFORE inserting the new Stripe row")
class SubscriptionServiceSiblingRetirementOrderTest {

    @Mock private StripeClient stripe;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private BillingEventRepository billingEventRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PriceRepository priceRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private PendingCreditUpgradeRepository pendingCreditUpgradeRepository;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;
    @Mock private OrganizationMemberRepository orgMemberRepository;
    @Mock private OrganizationService organizationService;
    @Mock private com.stripe.service.SubscriptionService stripeSubscriptions;

    private SubscriptionService service;

    private User user;
    private BillingCustomer bc;
    private Plan free;
    private Plan team;
    private Subscription freeRow;

    @BeforeEach
    void setUp() throws Exception {
        service = new SubscriptionService(stripe, subscriptionRepository, billingCustomerRepository,
                billingEventRepository, planRepository, priceRepository, userRepository,
                creditAttributionService, pendingCreditUpgradeRepository, new ObjectMapper(),
                quotaSyncer, orgMemberRepository, organizationService);

        user = new User();
        user.setId(1L);
        user.setUsername("u1");
        user.setEmail("u1@test.local");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));

        bc = new BillingCustomer(user, "internal");
        bc.setId(10L);
        bc.setProviderCustomerId("cus_test_10");

        free = new Plan("FREE", "Free", "free");
        free.setId(20L);
        team = new Plan("TEAM", "Team", "team");
        team.setId(23L);

        // The internal FREE row every user starts with: active, funded with its init credits AND a
        // PAYG top-up the user paid for. Both buckets must follow the user, separately.
        freeRow = new Subscription();
        freeRow.setId(70L);
        freeRow.setBillingCustomer(bc);
        freeRow.setPlan(free);
        freeRow.setProvider("internal");
        freeRow.setStatus("active");
        freeRow.setCadence("monthly");
        freeRow.setRemainingCredits(new BigDecimal("1000"));
        freeRow.setPaygRemainingCredits(new BigDecimal("8000"));
        freeRow.setCreatedAt(LocalDateTime.now().minusDays(8));

        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        lenient().when(stripeSub.getId()).thenReturn("sub_team_1");
        lenient().when(stripeSub.getCustomer()).thenReturn("cus_test_10");
        lenient().when(stripeSub.getCancelAtPeriodEnd()).thenReturn(false);
        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(item.getQuantity()).thenReturn(1L);
        SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
        lenient().when(items.getData()).thenReturn(List.of(item));
        lenient().when(stripeSub.getItems()).thenReturn(items);
        when(stripe.subscriptions()).thenReturn(stripeSubscriptions);
        when(stripeSubscriptions.retrieve(anyString(), any(), isNull())).thenReturn(stripeSub);

        when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
        when(planRepository.findById(23L)).thenReturn(Optional.of(team));
        lenient().when(subscriptionRepository.findByProviderSubscriptionId("sub_team_1")).thenReturn(Optional.empty());
        lenient().when(subscriptionRepository.findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(eq(1L), anyList()))
                .thenReturn(List.of(freeRow));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            if (s.getId() == null) s.setId(71L);
            return s;
        });
        lenient().when(subscriptionRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void upsert(String status) throws Exception {
        service.onSubscriptionUpsert("evt_team", "sub_team_1", status, 23L, null,
                LocalDateTime.now(), LocalDateTime.now().plusYears(1), 1L, null, 100, null);
    }

    @Test
    @DisplayName("FREE row is canceled and FLUSHED before the TEAM row is saved (V423 index would reject the reverse order)")
    void siblingIsRetiredAndFlushedBeforeTheNewRowIsSaved() throws Exception {
        upsert("active");

        InOrder order = inOrder(subscriptionRepository);
        ArgumentCaptor<List<Subscription>> retired = ArgumentCaptor.forClass(List.class);
        order.verify(subscriptionRepository).saveAllAndFlush(retired.capture());
        order.verify(subscriptionRepository).save(any(Subscription.class));

        assertThat(retired.getValue()).containsExactly(freeRow);
        assertThat(freeRow.getStatus()).isEqualTo("canceled");
        assertThat(freeRow.getCancelAtPeriodEnd()).isTrue();
        // A plain saveAll would be flushed AFTER the insert (Hibernate orders inserts first).
        verify(subscriptionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("both buckets are carried onto the TEAM row at its first save, sub and PAYG kept apart (V250)")
    void bothBucketsAreCarriedBeforeTheFirstSave() throws Exception {
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);

        upsert("active");

        verify(subscriptionRepository).save(saved.capture());
        Subscription teamRow = saved.getValue();
        assertThat(teamRow.getProviderSubscriptionId()).isEqualTo("sub_team_1");
        assertThat(teamRow.getPlan()).isSameAs(team);
        assertThat(teamRow.getRemainingCredits()).isEqualByComparingTo("1000");
        assertThat(teamRow.getPaygRemainingCredits()).isEqualByComparingTo("8000");
        assertThat(freeRow.getRemainingCredits()).isEqualByComparingTo("0");
        assertThat(freeRow.getPaygRemainingCredits()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a trialing target retires the sibling exactly like an active one (the index covers both statuses)")
    void trialingTargetRetiresSiblingToo() throws Exception {
        upsert("trialing");

        InOrder order = inOrder(subscriptionRepository);
        order.verify(subscriptionRepository).saveAllAndFlush(anyList());
        order.verify(subscriptionRepository).save(any(Subscription.class));
        assertThat(freeRow.getStatus()).isEqualTo("canceled");
    }

    @Test
    @DisplayName("a row already carrying the same provider subscription id is never retired as its own sibling")
    void sameProviderSubscriptionIdIsNotRetired() throws Exception {
        Subscription sameSub = new Subscription();
        sameSub.setId(72L);
        sameSub.setBillingCustomer(bc);
        sameSub.setPlan(team);
        sameSub.setProvider("stripe");
        sameSub.setProviderSubscriptionId("sub_team_1");
        sameSub.setStatus("incomplete");
        sameSub.setRemainingCredits(new BigDecimal("5"));
        when(subscriptionRepository.findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(eq(1L), anyList()))
                .thenReturn(List.of(sameSub, freeRow));

        upsert("active");

        ArgumentCaptor<List<Subscription>> retired = ArgumentCaptor.forClass(List.class);
        verify(subscriptionRepository).saveAllAndFlush(retired.capture());
        assertThat(retired.getValue()).containsExactly(freeRow);
        assertThat(sameSub.getStatus()).isEqualTo("incomplete");
        assertThat(sameSub.getRemainingCredits()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a non-active target status (e.g. canceled) retires nothing and flushes nothing")
    void nonActiveTargetDoesNotTouchSiblings() throws Exception {
        upsert("canceled");

        verify(subscriptionRepository, never()).saveAllAndFlush(anyList());
        assertThat(freeRow.getStatus()).isEqualTo("active");
        assertThat(freeRow.getRemainingCredits()).isEqualByComparingTo("1000");
        assertThat(freeRow.getPaygRemainingCredits()).isEqualByComparingTo("8000");
    }
}
