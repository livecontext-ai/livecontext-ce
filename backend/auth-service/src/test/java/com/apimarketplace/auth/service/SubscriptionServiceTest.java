package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService Tests")
class SubscriptionServiceTest {

    @Mock private StripeClient stripe;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private BillingEventRepository billingEventRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PriceRepository priceRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private com.apimarketplace.auth.repository.PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    @Mock private com.stripe.service.SubscriptionService stripeSubscriptionServiceMock;
    @Mock private com.stripe.service.CustomerService stripeCustomerServiceMock;

    private SubscriptionService subscriptionService;
    private ObjectMapper objectMapper;

    @Mock private PlanStorageQuotaSyncer quotaSyncer;
    @Mock private com.apimarketplace.auth.repository.OrganizationMemberRepository orgMemberRepository;
    @Mock private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        subscriptionService = new SubscriptionService(
                stripe,
                subscriptionRepository,
                billingCustomerRepository,
                billingEventRepository,
                planRepository,
                priceRepository,
                userRepository,
                creditAttributionService,
                pendingCreditUpgradeRepository,
                objectMapper,
                quotaSyncer,
                orgMemberRepository,
                organizationService
        );
    }

    // ===== Helper methods =====

    private User createTestUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("testuser" + id);
        user.setEmail("testuser" + id + "@test.com");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        return user;
    }

    private BillingCustomer createBillingCustomer(Long id, User user) {
        BillingCustomer bc = new BillingCustomer(user, "stripe");
        bc.setId(id);
        bc.setProviderCustomerId("cus_test_" + id);
        return bc;
    }

    private Plan createPlan(Long id, String code) {
        Plan plan = new Plan(code, code + " Plan", "Description for " + code);
        plan.setId(id);
        plan.setIncludedLlmTokens(1000L);
        plan.setIncludedStorageBytes(1073741824L); // 1 GB
        return plan;
    }

    private Price createPrice(Long id, Plan plan, String cadence, String providerPriceId) {
        Price price = new Price(plan, cadence, 999);
        price.setId(id);
        price.setProviderPriceId(providerPriceId);
        return price;
    }

    private com.apimarketplace.auth.domain.Subscription createExistingSubscription(
            Long id, BillingCustomer bc, Plan plan, String status) {
        com.apimarketplace.auth.domain.Subscription sub = new com.apimarketplace.auth.domain.Subscription();
        sub.setId(id);
        sub.setBillingCustomer(bc);
        sub.setPlan(plan);
        sub.setProvider("stripe");
        sub.setProviderSubscriptionId("sub_existing_" + id);
        sub.setStatus(status);
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        sub.setCancelAtPeriodEnd(false);
        sub.setCreatedAt(LocalDateTime.now().minusDays(30));
        sub.setUpdatedAt(LocalDateTime.now());
        return sub;
    }

    private com.stripe.model.Subscription createStripeSubscription(
            String subId, String customerId, boolean cancelAtPeriodEnd, int quantity) {
        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        lenient().when(stripeSub.getId()).thenReturn(subId);
        lenient().when(stripeSub.getCustomer()).thenReturn(customerId);
        lenient().when(stripeSub.getCancelAtPeriodEnd()).thenReturn(cancelAtPeriodEnd);

        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(item.getQuantity()).thenReturn((long) quantity);
        long now = System.currentTimeMillis() / 1000;
        lenient().when(item.getCurrentPeriodStart()).thenReturn(now - 86400L * 15);
        lenient().when(item.getCurrentPeriodEnd()).thenReturn(now + 86400L * 15);

        SubscriptionItemCollection itemCollection = mock(SubscriptionItemCollection.class);
        lenient().when(itemCollection.getData()).thenReturn(List.of(item));
        lenient().when(stripeSub.getItems()).thenReturn(itemCollection);

        return stripeSub;
    }

    private void setupStripeRetrieve(com.stripe.model.Subscription stripeSub) throws StripeException {
        lenient().when(stripe.subscriptions()).thenReturn(stripeSubscriptionServiceMock);
        lenient().when(stripeSubscriptionServiceMock.retrieve(anyString(), any(), isNull()))
                .thenReturn(stripeSub);
    }

    // ===== onSubscriptionUpsert =====

    @Nested
    @DisplayName("onSubscriptionUpsert")
    class OnSubscriptionUpsert {

        @Test
        @DisplayName("should create new subscription from Stripe event")
        void shouldCreateNewSubscription() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_new_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(priceRepository.findByProviderPriceId("price_starter_monthly")).thenReturn(Optional.empty());
            when(subscriptionRepository.findByProviderSubscriptionId("sub_new_1")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any(com.apimarketplace.auth.domain.Subscription.class)))
                    .thenAnswer(inv -> {
                        com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                        s.setId(100L);
                        return s;
                    });
            when(billingEventRepository.save(any(BillingEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            LocalDateTime start = LocalDateTime.now().minusDays(15);
            LocalDateTime end = LocalDateTime.now().plusDays(15);

            subscriptionService.onSubscriptionUpsert("evt_1", "sub_new_1", "active",
                    1L, "price_starter_monthly", start, end, 1L, null);

            verify(subscriptionRepository).save(any(com.apimarketplace.auth.domain.Subscription.class));
            verify(billingEventRepository).save(any(BillingEvent.class));
        }

        @Test
        @DisplayName("should update existing subscription with status change active to canceled")
        void shouldUpdateExistingSubscriptionStatusChange() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription existing = createExistingSubscription(10L, bc, plan, "active");
            existing.setProviderSubscriptionId("sub_update_1");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_update_1", "cus_test_1", true, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_update_1")).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_2", "sub_update_1", "canceled",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("canceled");
        }

        @Test
        @DisplayName("should update existing subscription with plan change FREE to STARTER")
        void shouldUpdateExistingSubscriptionPlanChange() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan freePlan = createPlan(1L, "FREE");
            Plan starterPlan = createPlan(2L, "STARTER");
            com.apimarketplace.auth.domain.Subscription existing = createExistingSubscription(10L, bc, freePlan, "active");
            existing.setProviderSubscriptionId("sub_planchange_1");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_planchange_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(2L)).thenReturn(Optional.of(starterPlan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_planchange_1")).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_3", "sub_planchange_1", "active",
                    2L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

        }

        @Test
        @DisplayName("V198 regression: plan change delegates to PlanStorageQuotaSyncer with the new plan")
        void planChangeDelegatesToSyncer() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan freePlan = createPlan(1L, "FREE");
            freePlan.setIncludedStorageBytes(104_857_600L);
            Plan starterPlan = createPlan(2L, "STARTER");
            starterPlan.setIncludedStorageBytes(1_073_741_824L);

            com.apimarketplace.auth.domain.Subscription existing =
                    createExistingSubscription(10L, bc, freePlan, "active");
            existing.setProviderSubscriptionId("sub_quota_sync_1");

            com.stripe.model.Subscription stripeSub =
                    createStripeSubscription("sub_quota_sync_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(2L)).thenReturn(Optional.of(starterPlan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_quota_sync_1"))
                    .thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_quota_sync", "sub_quota_sync_1", "active",
                    2L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            verify(quotaSyncer).syncAfterCommit(1L, starterPlan);
            // V311: a plan change also reconciles the owner's workspaces to the new cap.
            verify(organizationService).reconcileWorkspacePauseState(1L);
        }

        @Test
        @DisplayName("V198 regression: status-only update (same plan) does NOT delegate to syncer (no thrash)")
        void statusOnlyUpdateDoesNotInvokeSyncer() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan starterPlan = createPlan(2L, "STARTER");
            starterPlan.setIncludedStorageBytes(1_073_741_824L);
            com.apimarketplace.auth.domain.Subscription existing =
                    createExistingSubscription(10L, bc, starterPlan, "active");
            existing.setProviderSubscriptionId("sub_no_thrash_1");

            com.stripe.model.Subscription stripeSub =
                    createStripeSubscription("sub_no_thrash_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(2L)).thenReturn(Optional.of(starterPlan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_no_thrash_1"))
                    .thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_no_thrash", "sub_no_thrash_1", "active",
                    2L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            verify(quotaSyncer, never()).syncAfterCommit(any(), any());
            // V311: no plan change → no workspace reconcile (no thrash).
            verify(organizationService, never()).reconcileWorkspacePauseState(any());
        }

        @Test
        @DisplayName("should create subscription with trialing status")
        void shouldCreateSubscriptionWithTrialingStatus() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_trial_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_trial_1")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(101L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_trial", "sub_trial_1", "trialing",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(14), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("trialing");
        }

        @Test
        @DisplayName("skips silently (no throw, no DB writes) when BillingCustomer is missing for the nonce userId - orphan Stripe sub handling (regression: pre-fix threw IllegalStateException → WebhookController logged 2 ERROR stack traces per event; observed 8+/day in prod 2026-05 for userId=103)")
        void shouldSkipSilentlyWhenBillingCustomerMissing() throws Exception {
            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_nobc", "cus_test_99", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(99L)).thenReturn(Optional.empty());

            // Must NOT throw - webhook idempotent-OK so Stripe returns 200 and stops retrying
            subscriptionService.onSubscriptionUpsert("evt_nobc", "sub_nobc", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 99L, null);

            // Verify the orphan path skipped before any DB write: no subscription saved,
            // no billing event saved, no plan lookup, no credit attribution attempted.
            verify(subscriptionRepository, never()).save(any());
            verify(billingEventRepository, never()).save(any());
            verify(planRepository, never()).findById(any());
            verify(creditAttributionService, never()).attributeOnSubscription(anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("should handle race condition via DataIntegrityViolationException fallback")
        void shouldHandleRaceConditionWithFallback() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_race_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

            com.apimarketplace.auth.domain.Subscription raceWinner = createExistingSubscription(50L, bc, plan, "active");
            raceWinner.setProviderSubscriptionId("sub_race_1");

            when(subscriptionRepository.findByProviderSubscriptionId("sub_race_1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(raceWinner));

            // First save throws DataIntegrityViolationException, second succeeds
            when(subscriptionRepository.save(any(com.apimarketplace.auth.domain.Subscription.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate key"))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_race", "sub_race_1", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            // Should have called findByProviderSubscriptionId twice (once normally, once in fallback)
            verify(subscriptionRepository, times(2)).findByProviderSubscriptionId("sub_race_1");
            // Two save calls: first fails, second succeeds
            verify(subscriptionRepository, times(2)).save(any(com.apimarketplace.auth.domain.Subscription.class));
        }

        @Test
        @DisplayName("should cancel sibling active subscriptions when new one becomes active")
        void shouldCancelSiblingActiveSubscriptions() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.apimarketplace.auth.domain.Subscription sibling = createExistingSubscription(20L, bc, plan, "active");
            sibling.setProviderSubscriptionId("sub_old_sibling");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_new_active", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_new_active")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any(com.apimarketplace.auth.domain.Subscription.class)))
                    .thenAnswer(inv -> {
                        com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                        s.setId(30L);
                        return s;
                    });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(subscriptionRepository.findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(1L), anyList())).thenReturn(List.of(sibling));

            subscriptionService.onSubscriptionUpsert("evt_sibling", "sub_new_active", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            assertThat(sibling.getStatus()).isEqualTo("canceled");
            assertThat(sibling.getCancelAtPeriodEnd()).isTrue();
        }

        @Test
        @DisplayName("V250 regression: carries PAYG bucket alongside sub bucket when canceling sibling - without this, a user with paid PAYG top-up silently loses every dollar on plan upgrade")
        void carriesPaygBucketOnSiblingCancel() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            // Sibling has BOTH buckets populated: 50 sub credits (renewal grant) + 200 PAYG
            // credits (user paid Stripe top-up). Pre-fix the carry-over loop reads only
            // remainingCredits, so the 200 PAYG dollars vanish when this sibling is
            // canceled during the plan upgrade upsert.
            com.apimarketplace.auth.domain.Subscription sibling = createExistingSubscription(20L, bc, plan, "active");
            sibling.setProviderSubscriptionId("sub_old_sibling");
            sibling.setRemainingCredits(new java.math.BigDecimal("50"));
            sibling.setPaygRemainingCredits(new java.math.BigDecimal("200"));

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_new_active", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_new_active")).thenReturn(Optional.empty());

            // Capture the FINAL state of the new subscription that gets persisted.
            java.util.concurrent.atomic.AtomicReference<com.apimarketplace.auth.domain.Subscription> savedNewSub =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(subscriptionRepository.save(any(com.apimarketplace.auth.domain.Subscription.class)))
                    .thenAnswer(inv -> {
                        com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                        if (s.getId() == null) s.setId(30L);
                        if (!s.getId().equals(20L)) {
                            // The "new" sub (not the sibling). Track every save so the assertion
                            // sees the post-carry-over balance, not the initial zero state.
                            savedNewSub.set(s);
                        }
                        return s;
                    });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(subscriptionRepository.findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(1L), anyList())).thenReturn(List.of(sibling));

            subscriptionService.onSubscriptionUpsert("evt_payg_carry", "sub_new_active", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            // Sibling drained on BOTH buckets so the PAYG dollars are not double-spendable.
            assertThat(sibling.getStatus()).isEqualTo("canceled");
            assertThat(sibling.getRemainingCredits()).isEqualByComparingTo("0");
            assertThat(sibling.getPaygRemainingCredits()).isEqualByComparingTo("0");

            // New sub carries BOTH buckets independently - PAYG must NOT collapse into
            // sub-bucket (it would get wiped at next renewal).
            assertThat(savedNewSub.get()).isNotNull();
            assertThat(savedNewSub.get().getRemainingCredits()).isEqualByComparingTo("50");
            assertThat(savedNewSub.get().getPaygRemainingCredits()).isEqualByComparingTo("200");
        }

        @Test
        @DisplayName("should trigger quota transition when plan changes")
        void shouldTriggerQuotaTransitionOnPlanChange() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan freePlan = createPlan(1L, "FREE");
            Plan proPlan = createPlan(3L, "PRO");
            com.apimarketplace.auth.domain.Subscription existing = createExistingSubscription(10L, bc, freePlan, "active");
            existing.setProviderSubscriptionId("sub_upgrade_1");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_upgrade_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(3L)).thenReturn(Optional.of(proPlan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_upgrade_1")).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_upgrade", "sub_upgrade_1", "active",
                    3L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

        }

        @Test
        @DisplayName("should resolve Price from providerPriceId")
        void shouldResolvePriceFromProviderPriceId() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            Price price = createPrice(1L, plan, "yearly", "price_starter_yearly");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_price_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(priceRepository.findByProviderPriceId("price_starter_yearly")).thenReturn(Optional.of(price));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_price_1")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(102L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_price", "sub_price_1", "active",
                    1L, "price_starter_yearly", LocalDateTime.now(), LocalDateTime.now().plusDays(365), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getPrice()).isEqualTo(price);
            assertThat(captor.getValue().getCadence()).isEqualTo("yearly");
        }

        @Test
        @DisplayName("should handle unknown providerPriceId gracefully and default to monthly cadence")
        void shouldHandleUnknownPriceGracefully() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_noprice_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(priceRepository.findByProviderPriceId("price_unknown")).thenReturn(Optional.empty());
            when(subscriptionRepository.findByProviderSubscriptionId("sub_noprice_1")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(103L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_noprice", "sub_noprice_1", "active",
                    1L, "price_unknown", LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getPrice()).isNull();
            assertThat(captor.getValue().getCadence()).isEqualTo("monthly");
        }

        @Test
        @DisplayName("should throw when plan is not found")
        void shouldThrowWhenPlanNotFound() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_noplan", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    subscriptionService.onSubscriptionUpsert("evt_noplan", "sub_noplan", "active",
                            999L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("Plan not found");
        }

        @Test
        @DisplayName("should not trigger quota transition when plan does not change")
        void shouldNotTriggerQuotaTransitionWhenPlanSame() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription existing = createExistingSubscription(10L, bc, plan, "active");
            existing.setProviderSubscriptionId("sub_same_plan");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_same_plan", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_same_plan")).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_same", "sub_same_plan", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

        }

        @Test
        @DisplayName("should set cancelAtPeriodEnd from Stripe subscription")
        void shouldSetCancelAtPeriodEndFromStripe() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_cancel_end", "cus_test_1", true, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_cancel_end")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(104L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_cancel_end", "sub_cancel_end", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getCancelAtPeriodEnd()).isTrue();
        }

        @Test
        @DisplayName("should handle null status and default to active")
        void shouldDefaultToActiveWhenStatusNull() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_null_status", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_null_status")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(105L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_nullstatus", "sub_null_status", null,
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("active");
        }

        @Test
        @DisplayName("should set quantity from Stripe subscription items")
        void shouldSetQuantityFromStripe() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_qty", "cus_test_1", false, 5);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_qty")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(106L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_qty", "sub_qty", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("should trigger quota transition when old plan is null (first subscription)")
        void shouldTriggerQuotaTransitionWhenOldPlanNull() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_first", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_first")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(107L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_first", "sub_first", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

        }

        @Test
        @DisplayName("should save a BillingEvent for each upsert")
        void shouldSaveBillingEvent() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_evt_1", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_evt_1")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(108L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_billing", "sub_evt_1", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<BillingEvent> captor = ArgumentCaptor.forClass(BillingEvent.class);
            verify(billingEventRepository).save(captor.capture());
            BillingEvent savedEvent = captor.getValue();
            assertThat((String) ReflectionTestUtils.getField(savedEvent, "type")).isEqualTo("subscription.upserted");
            assertThat((String) ReflectionTestUtils.getField(savedEvent, "provider")).isEqualTo("stripe");
        }

        @Test
        @DisplayName("should record nonce in billing event payload when provided")
        void shouldRecordNonceInBillingEvent() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_nonce", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_nonce")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(109L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_nonce", "sub_nonce", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, "test_nonce_123");

            ArgumentCaptor<BillingEvent> captor = ArgumentCaptor.forClass(BillingEvent.class);
            verify(billingEventRepository).save(captor.capture());
            com.fasterxml.jackson.databind.JsonNode payload = (com.fasterxml.jackson.databind.JsonNode) ReflectionTestUtils.getField(captor.getValue(), "payload");
            assertThat(payload.has("nonce")).isTrue();
            assertThat(payload.get("nonce").asText()).isEqualTo("test_nonce_123");
        }

        @Test
        @DisplayName("should not cancel siblings when subscription status is canceled")
        void shouldNotCancelSiblingsWhenStatusCanceled() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_canceled_no_sibling", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_canceled_no_sibling")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(110L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_canceled_nosib", "sub_canceled_no_sibling", "canceled",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            verify(subscriptionRepository, never())
                    .findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(anyLong(), anyList());
        }

        @Test
        @DisplayName("should use fallback overload without userId and nonce")
        void shouldUseFallbackOverload() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_fallback", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByProviderCustomerId("cus_test_1")).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_fallback")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(111L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_fallback", "sub_fallback", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30));

            verify(subscriptionRepository).save(any(com.apimarketplace.auth.domain.Subscription.class));
        }

        @Test
        @DisplayName("should set provider to stripe")
        void shouldSetProviderToStripe() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_provider", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_provider")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(112L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_provider", "sub_provider", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<com.apimarketplace.auth.domain.Subscription> captor =
                    ArgumentCaptor.forClass(com.apimarketplace.auth.domain.Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getProvider()).isEqualTo("stripe");
        }

        @Test
        @DisplayName("should set billing event action to subscription_created for new subscription")
        void shouldSetBillingEventActionCreated() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_action_new", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_action_new")).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any())).thenAnswer(inv -> {
                com.apimarketplace.auth.domain.Subscription s = inv.getArgument(0);
                s.setId(113L);
                return s;
            });
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_action_new", "sub_action_new", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<BillingEvent> captor = ArgumentCaptor.forClass(BillingEvent.class);
            verify(billingEventRepository).save(captor.capture());
            com.fasterxml.jackson.databind.JsonNode actionPayload = (com.fasterxml.jackson.databind.JsonNode) ReflectionTestUtils.getField(captor.getValue(), "payload");
            assertThat(actionPayload.get("action").asText()).isEqualTo("subscription_created");
        }

        @Test
        @DisplayName("should set billing event action to subscription_updated for existing subscription")
        void shouldSetBillingEventActionUpdated() throws Exception {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription existing = createExistingSubscription(10L, bc, plan, "active");
            existing.setProviderSubscriptionId("sub_action_update");

            com.stripe.model.Subscription stripeSub = createStripeSubscription("sub_action_update", "cus_test_1", false, 1);
            setupStripeRetrieve(stripeSub);

            when(billingCustomerRepository.findByUserId(1L)).thenReturn(Optional.of(bc));
            when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
            when(subscriptionRepository.findByProviderSubscriptionId("sub_action_update")).thenReturn(Optional.of(existing));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.onSubscriptionUpsert("evt_action_update", "sub_action_update", "active",
                    1L, null, LocalDateTime.now(), LocalDateTime.now().plusDays(30), 1L, null);

            ArgumentCaptor<BillingEvent> captor = ArgumentCaptor.forClass(BillingEvent.class);
            verify(billingEventRepository).save(captor.capture());
            com.fasterxml.jackson.databind.JsonNode updatePayload = (com.fasterxml.jackson.databind.JsonNode) ReflectionTestUtils.getField(captor.getValue(), "payload");
            assertThat(updatePayload.get("action").asText()).isEqualTo("subscription_updated");
        }
    }

    // ===== cancelSubscription =====

    @Nested
    @DisplayName("cancelSubscription")
    class CancelSubscription {

        @Test
        @DisplayName("should cancel active subscription")
        void shouldCancelActiveSubscription() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");
            sub.setProviderSubscriptionId("sub_cancel_1");

            when(subscriptionRepository.findByProviderSubscriptionId("sub_cancel_1"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.cancelSubscription("sub_cancel_1");

            assertThat(sub.getStatus()).isEqualTo("canceled");
            assertThat(sub.getCancelAtPeriodEnd()).isTrue();
            verify(subscriptionRepository).save(sub);
        }

        @Test
        @DisplayName("should cancel already-canceled subscription idempotently")
        void shouldCancelAlreadyCanceledSubscription() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "canceled");
            sub.setProviderSubscriptionId("sub_already_canceled");
            sub.setCancelAtPeriodEnd(true);

            when(subscriptionRepository.findByProviderSubscriptionId("sub_already_canceled"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.cancelSubscription("sub_already_canceled");

            assertThat(sub.getStatus()).isEqualTo("canceled");
            assertThat(sub.getCancelAtPeriodEnd()).isTrue();
            verify(subscriptionRepository).save(sub);
        }

        @Test
        @DisplayName("should be no-op when subscription does not exist")
        void shouldBeNoOpWhenSubscriptionDoesNotExist() {
            when(subscriptionRepository.findByProviderSubscriptionId("sub_nonexistent"))
                    .thenReturn(Optional.empty());

            subscriptionService.cancelSubscription("sub_nonexistent");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set cancelAtPeriodEnd to true on cancel")
        void shouldSetCancelAtPeriodEndToTrue() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");
            sub.setProviderSubscriptionId("sub_period_end");
            sub.setCancelAtPeriodEnd(false);

            when(subscriptionRepository.findByProviderSubscriptionId("sub_period_end"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.cancelSubscription("sub_period_end");

            assertThat(sub.getCancelAtPeriodEnd()).isTrue();
        }

        @Test
        @DisplayName("should update the updatedAt timestamp on cancel")
        void shouldUpdateTimestampOnCancel() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");
            sub.setProviderSubscriptionId("sub_ts_update");
            LocalDateTime before = LocalDateTime.now().minusDays(5);
            sub.setUpdatedAt(before);

            when(subscriptionRepository.findByProviderSubscriptionId("sub_ts_update"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.cancelSubscription("sub_ts_update");

            assertThat(sub.getUpdatedAt()).isAfter(before);
        }

        @Test
        @DisplayName("reconciles workspace pause state on cancel so an over-cap owner's workspaces pause after the plan lapses to FREE")
        void shouldReconcileWorkspacePauseStateOnCancel() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "PRO");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");
            sub.setProviderSubscriptionId("sub_cancel_reconcile");

            when(subscriptionRepository.findByProviderSubscriptionId("sub_cancel_reconcile"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            subscriptionService.cancelSubscription("sub_cancel_reconcile");

            // The deletion/cancel path is the only downgrade that bypasses onSubscriptionUpsert;
            // without the reconcile here a cancelled owner keeps every over-cap workspace.
            verify(organizationService).reconcileWorkspacePauseState(1L);
        }

        @Test
        @DisplayName("a workspace reconcile failure does not fail the cancellation")
        void reconcileFailureDoesNotFailCancel() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "PRO");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");
            sub.setProviderSubscriptionId("sub_cancel_reconcile_fail");

            when(subscriptionRepository.findByProviderSubscriptionId("sub_cancel_reconcile_fail"))
                    .thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("boom")).when(organizationService).reconcileWorkspacePauseState(1L);

            subscriptionService.cancelSubscription("sub_cancel_reconcile_fail");

            // Cancellation still completes even though reconcile threw.
            assertThat(sub.getStatus()).isEqualTo("canceled");
            verify(subscriptionRepository).save(sub);
            verify(organizationService).reconcileWorkspacePauseState(1L);
        }
    }

    @Nested
    @DisplayName("reconcileWorkspacesAfterPlanLoss")
    class ReconcileWorkspacesAfterPlanLoss {

        @Test
        @DisplayName("is a no-op for a null owner")
        void noOpForNullOwner() {
            subscriptionService.reconcileWorkspacesAfterPlanLoss(null);

            verify(organizationService, never()).reconcileWorkspacePauseState(any());
        }

        @Test
        @DisplayName("reconciles the owner - shared by the customer-deletion + Stripe-cancel paths that bypass onSubscriptionUpsert")
        void reconcilesOwner() {
            subscriptionService.reconcileWorkspacesAfterPlanLoss(42L);

            verify(organizationService).reconcileWorkspacePauseState(42L);
        }

        @Test
        @DisplayName("swallows a reconcile failure so it never fails the cancellation/webhook")
        void swallowsReconcileFailure() {
            doThrow(new RuntimeException("boom")).when(organizationService).reconcileWorkspacePauseState(42L);

            // Must NOT propagate - if it did, this call would fail the test.
            subscriptionService.reconcileWorkspacesAfterPlanLoss(42L);

            verify(organizationService).reconcileWorkspacePauseState(42L);
        }
    }

    // ===== existsByProviderSubscriptionId =====

    @Nested
    @DisplayName("existsByProviderSubscriptionId")
    class ExistsByProviderSubscriptionId {

        @Test
        @DisplayName("should return true when subscription exists")
        void shouldReturnTrueWhenExists() {
            when(subscriptionRepository.existsByProviderSubscriptionId("sub_exists"))
                    .thenReturn(true);

            boolean result = subscriptionService.existsByProviderSubscriptionId("sub_exists");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when subscription does not exist")
        void shouldReturnFalseWhenNotExists() {
            when(subscriptionRepository.existsByProviderSubscriptionId("sub_not_exists"))
                    .thenReturn(false);

            boolean result = subscriptionService.existsByProviderSubscriptionId("sub_not_exists");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fallback to findIdByProviderSubscriptionIdLight when exists throws")
        void shouldFallbackOnException() {
            when(subscriptionRepository.existsByProviderSubscriptionId("sub_fallback_exists"))
                    .thenThrow(new RuntimeException("Driver issue"));
            when(subscriptionRepository.findIdByProviderSubscriptionIdLight("sub_fallback_exists"))
                    .thenReturn(Optional.of(42L));

            boolean result = subscriptionService.existsByProviderSubscriptionId("sub_fallback_exists");

            assertThat(result).isTrue();
        }
    }

    // ===== findByProviderSubscriptionId =====

    @Nested
    @DisplayName("findByProviderSubscriptionId")
    class FindByProviderSubscriptionId {

        @Test
        @DisplayName("should return subscription when found")
        void shouldReturnSubscriptionWhenFound() {
            User user = createTestUser(1L);
            BillingCustomer bc = createBillingCustomer(1L, user);
            Plan plan = createPlan(1L, "STARTER");
            com.apimarketplace.auth.domain.Subscription sub = createExistingSubscription(10L, bc, plan, "active");

            when(subscriptionRepository.findByProviderSubscriptionId("sub_existing_10"))
                    .thenReturn(Optional.of(sub));

            Optional<com.apimarketplace.auth.domain.Subscription> result =
                    subscriptionService.findByProviderSubscriptionId("sub_existing_10");

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            when(subscriptionRepository.findByProviderSubscriptionId("sub_gone"))
                    .thenReturn(Optional.empty());

            Optional<com.apimarketplace.auth.domain.Subscription> result =
                    subscriptionService.findByProviderSubscriptionId("sub_gone");

            assertThat(result).isEmpty();
        }
    }

}
