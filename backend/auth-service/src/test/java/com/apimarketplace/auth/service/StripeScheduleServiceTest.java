package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.domain.dto.PlanChangeResult;
import com.apimarketplace.auth.domain.dto.ScheduledChangeInfo;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.service.SubscriptionScheduleService;
import com.stripe.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StripeScheduleService.
 *
 * Since StripeScheduleService relies on Stripe SDK's StripeClient (SDK 31+),
 * we mock the StripeClient and its service chain.
 * Tests focus on:
 * - Downgrade scheduling logic and result types
 * - Billing cycle change scheduling
 * - Scheduled change retrieval and cancellation
 * - Validation and error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeScheduleService Tests")
class StripeScheduleServiceTest {

    @Mock
    private StripeClient stripe;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private BillingCustomerRepository billingCustomerRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceCacheService priceCacheService;

    @Mock
    private PlanCacheService planCacheService;

    // Stripe service mocks
    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private SubscriptionScheduleService subscriptionScheduleService;

    @InjectMocks
    private StripeScheduleService stripeScheduleService;

    // Test data constants
    private static final Long USER_ID = 1L;
    private static final String STRIPE_SUB_ID = "sub_test123";
    private static final String STRIPE_SCHEDULE_ID = "sub_sched_test123";
    private static final String STRIPE_PRICE_ID_PRO_MONTHLY = "price_pro_monthly";
    private static final String STRIPE_PRICE_ID_STARTER_MONTHLY = "price_starter_monthly";
    private static final String STRIPE_PRICE_ID_PRO_YEARLY = "price_pro_yearly";

    // ======================== HELPER METHODS ========================

    private User buildUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername("testuser");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        return user;
    }

    private Plan buildPlan(Long id, String code, String name) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setCode(code);
        plan.setName(name);
        return plan;
    }

    private Price buildPrice(Long id, Plan plan, String cadence, String providerPriceId) {
        Price price = new Price();
        price.setId(id);
        price.setPlan(plan);
        price.setCadence(cadence);
        price.setProviderPriceId(providerPriceId);
        return price;
    }

    private BillingCustomer buildBillingCustomer(Long id, User user, String providerCustomerId) {
        BillingCustomer bc = new BillingCustomer(user, "stripe");
        bc.setId(id);
        bc.setProviderCustomerId(providerCustomerId);
        return bc;
    }

    private Subscription buildSubscription(Long id, BillingCustomer bc, Plan plan, Price price,
                                           String providerSubId, String status) {
        Subscription sub = new Subscription();
        sub.setId(id);
        sub.setBillingCustomer(bc);
        sub.setPlan(plan);
        sub.setPrice(price);
        sub.setCadence("monthly");
        sub.setProviderSubscriptionId(providerSubId);
        sub.setStatus(status);
        sub.setQuantity(1);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        sub.setCancelAtPeriodEnd(false);
        sub.setCreatedAt(LocalDateTime.now().minusDays(30));
        sub.setUpdatedAt(LocalDateTime.now());
        return sub;
    }

    private com.stripe.model.Subscription buildStripeSubscription(String subId, String status,
                                                                   String priceId, String scheduleId) {
        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        lenient().when(stripeSub.getId()).thenReturn(subId);
        lenient().when(stripeSub.getStatus()).thenReturn(status);
        lenient().when(stripeSub.getCancelAtPeriodEnd()).thenReturn(false);
        lenient().when(stripeSub.getSchedule()).thenReturn(scheduleId);

        // Build subscription items for SDK 31+
        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(item.getId()).thenReturn("si_test123");
        lenient().when(item.getQuantity()).thenReturn(1L);
        long now = System.currentTimeMillis() / 1000;
        lenient().when(item.getCurrentPeriodStart()).thenReturn(now - 86400 * 15);
        lenient().when(item.getCurrentPeriodEnd()).thenReturn(now + 86400 * 15);

        com.stripe.model.Price stripePrice = mock(com.stripe.model.Price.class);
        lenient().when(stripePrice.getCurrency()).thenReturn("eur");
        lenient().when(stripePrice.getId()).thenReturn(priceId);
        lenient().when(item.getPrice()).thenReturn(stripePrice);

        SubscriptionItemCollection itemCollection = mock(SubscriptionItemCollection.class);
        lenient().when(itemCollection.getData()).thenReturn(List.of(item));
        lenient().when(stripeSub.getItems()).thenReturn(itemCollection);

        return stripeSub;
    }

    private SubscriptionSchedule buildSchedule(String scheduleId, String status, Long phase1Start,
                                                Long phase1End, String phase1PriceId,
                                                Long phase2Start, String phase2PriceId) {
        SubscriptionSchedule schedule = mock(SubscriptionSchedule.class);
        lenient().when(schedule.getId()).thenReturn(scheduleId);
        lenient().when(schedule.getStatus()).thenReturn(status);

        if (phase1PriceId != null && phase2PriceId != null) {
            // Phase 1 (current)
            SubscriptionSchedule.Phase phase1 = mock(SubscriptionSchedule.Phase.class);
            lenient().when(phase1.getStartDate()).thenReturn(phase1Start);
            lenient().when(phase1.getEndDate()).thenReturn(phase1End);
            SubscriptionSchedule.Phase.Item item1 = mock(SubscriptionSchedule.Phase.Item.class);
            lenient().when(item1.getPrice()).thenReturn(phase1PriceId);
            lenient().when(phase1.getItems()).thenReturn(List.of(item1));

            // Phase 2 (future)
            SubscriptionSchedule.Phase phase2 = mock(SubscriptionSchedule.Phase.class);
            lenient().when(phase2.getStartDate()).thenReturn(phase2Start != null ? phase2Start : phase1End);
            SubscriptionSchedule.Phase.Item item2 = mock(SubscriptionSchedule.Phase.Item.class);
            lenient().when(item2.getPrice()).thenReturn(phase2PriceId);
            lenient().when(phase2.getItems()).thenReturn(List.of(item2));

            lenient().when(schedule.getPhases()).thenReturn(List.of(phase1, phase2));
        } else {
            lenient().when(schedule.getPhases()).thenReturn(List.of());
        }

        return schedule;
    }

    private void setupStripeServiceChain() throws StripeException {
        lenient().when(stripe.subscriptions()).thenReturn(subscriptionService);
        lenient().when(stripe.subscriptionSchedules()).thenReturn(subscriptionScheduleService);
    }

    private void setupPlanHierarchy() {
        // FREE=1, STARTER=2, PRO=3, ENTERPRISE_BASIC=4
        lenient().when(planCacheService.getPlanOrder("FREE")).thenReturn(Optional.of(1L));
        lenient().when(planCacheService.getPlanOrder("STARTER")).thenReturn(Optional.of(2L));
        lenient().when(planCacheService.getPlanOrder("PRO")).thenReturn(Optional.of(3L));
        lenient().when(planCacheService.getPlanOrder("ENTERPRISE_BASIC")).thenReturn(Optional.of(4L));
    }

    // ======================== TEST CLASSES ========================

    @Nested
    @DisplayName("scheduleDowngrade")
    class ScheduleDowngrade {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should schedule PRO to STARTER downgrade and return SCHEDULED_DOWNGRADE result")
        void shouldScheduleProToStarterDowngrade() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            // First call: from scheduleDowngrade, second call: from createOrUpdateSchedule
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule createdSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );

            when(subscriptionScheduleService.create(any(SubscriptionScheduleCreateParams.class))).thenReturn(createdSchedule);
            when(subscriptionScheduleService.update(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleUpdateParams.class))).thenReturn(createdSchedule);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.SCHEDULED_DOWNGRADE);
            assertThat(result.getCurrentPlanCode()).isEqualTo("PRO");
            assertThat(result.getTargetPlanCode()).isEqualTo("STARTER");
            assertThat(result.getScheduleId()).isEqualTo(STRIPE_SCHEDULE_ID);
            assertThat(result.getEffectiveDate()).isNotNull();
        }

        @Test
        @DisplayName("should schedule PRO to FREE downgrade")
        void shouldScheduleProToFreeDowngrade() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("FREE", "monthly")).thenReturn(Optional.of("price_free"));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule createdSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, "price_free"
            );

            when(subscriptionScheduleService.create(any(SubscriptionScheduleCreateParams.class))).thenReturn(createdSchedule);
            when(subscriptionScheduleService.update(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleUpdateParams.class))).thenReturn(createdSchedule);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "FREE");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.SCHEDULED_DOWNGRADE);
            assertThat(result.getTargetPlanCode()).isEqualTo("FREE");
        }

        @Test
        @DisplayName("should return error when attempting to schedule an upgrade as downgrade")
        void shouldReturnErrorForUpgradeAsDowngrade() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "PRO");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
            assertThat(result.getMessage()).contains("not a downgrade");
        }

        @Test
        @DisplayName("should return error when no active subscription exists")
        void shouldReturnErrorWhenNoActiveSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
        }

        @Test
        @DisplayName("should return error when subscription has no provider ID")
        void shouldReturnErrorWhenNoProviderSubId() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, null, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("No linked Stripe subscription");
        }

        @Test
        @DisplayName("should handle Stripe exception gracefully and return error")
        void shouldHandleStripeExceptionGracefully() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            lenient().when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            lenient().when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));

            // Stripe throws during retrieve - before price cache is ever queried
            InvalidRequestException stripeEx = new InvalidRequestException(
                    "No such subscription", null, "resource_missing", null, 404, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenThrow(stripeEx);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
            assertThat(result.getMessage()).contains("Stripe error");
        }

        @Test
        @DisplayName("should update existing schedule when one already exists on the subscription")
        void shouldUpdateExistingSchedule() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));

            // First call returns sub without schedule (for scheduleDowngrade), second returns with schedule (for createOrUpdateSchedule)
            com.stripe.model.Subscription stripeSub1 = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            com.stripe.model.Subscription stripeSub2 = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub1, stripeSub2);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule existingSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, "price_old_target"
            );

            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(existingSchedule);

            SubscriptionSchedule updatedSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.update(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleUpdateParams.class))).thenReturn(updatedSchedule);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            // Should have retrieved existing schedule, not created a new one
            verify(subscriptionScheduleService).retrieve(STRIPE_SCHEDULE_ID);
            verify(subscriptionScheduleService, never()).create(any(SubscriptionScheduleCreateParams.class));
        }
    }

    @Nested
    @DisplayName("scheduleBillingCycleChange")
    class ScheduleBillingCycleChange {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should schedule monthly to yearly billing cycle change")
        void shouldScheduleMonthlyToYearly() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "yearly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_YEARLY));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule createdSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_PRO_YEARLY
            );

            when(subscriptionScheduleService.create(any(SubscriptionScheduleCreateParams.class))).thenReturn(createdSchedule);
            when(subscriptionScheduleService.update(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleUpdateParams.class))).thenReturn(createdSchedule);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "yearly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.BILLING_CYCLE_CHANGE);
            assertThat(result.getScheduleId()).isEqualTo(STRIPE_SCHEDULE_ID);
        }

        @Test
        @DisplayName("should schedule yearly to monthly billing cycle change")
        void shouldScheduleYearlyToMonthly() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "yearly", STRIPE_PRICE_ID_PRO_YEARLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_YEARLY)).thenReturn(Optional.of("yearly"));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_YEARLY, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule createdSchedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 180, now + 86400 * 180, STRIPE_PRICE_ID_PRO_YEARLY,
                    now + 86400 * 180, STRIPE_PRICE_ID_PRO_MONTHLY
            );

            when(subscriptionScheduleService.create(any(SubscriptionScheduleCreateParams.class))).thenReturn(createdSchedule);
            when(subscriptionScheduleService.update(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleUpdateParams.class))).thenReturn(createdSchedule);

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.BILLING_CYCLE_CHANGE);
        }

        @Test
        @DisplayName("should return NO_CHANGE when same cycle is requested")
        void shouldReturnNoChangeForSameCycle() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.NO_CHANGE);
            assertThat(result.getCurrentPlanCode()).isEqualTo("PRO");
        }

        @Test
        @DisplayName("should return error when no active subscription exists")
        void shouldReturnErrorWhenNoActiveSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "yearly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
        }

        @Test
        @DisplayName("should return error when target price is not found")
        void shouldReturnErrorWhenTargetPriceNotFound() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "yearly")).thenReturn(Optional.empty());

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "yearly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
        }
    }

    @Nested
    @DisplayName("getScheduledChange")
    class GetScheduledChange {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should return scheduled change when exists")
        void shouldReturnScheduledChangeWhenExists() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            // Price resolution
            Map<String, String> allPrices = Map.of(
                    "STARTER_monthly", STRIPE_PRICE_ID_STARTER_MONTHLY,
                    "PRO_monthly", STRIPE_PRICE_ID_PRO_MONTHLY
            );
            when(priceCacheService.getAllCachedPrices()).thenReturn(allPrices);
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));

            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isPresent();
            ScheduledChangeInfo info = result.get();
            assertThat(info.getScheduleId()).isEqualTo(STRIPE_SCHEDULE_ID);
            assertThat(info.getCurrentPlanCode()).isEqualTo("PRO");
            assertThat(info.getTargetPlanCode()).isEqualTo("STARTER");
            assertThat(info.isCancellable()).isTrue();
        }

        @Test
        @DisplayName("should return empty when no schedule exists")
        void shouldReturnEmptyWhenNoScheduleExists() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no active subscription exists")
        void shouldReturnEmptyWhenNoActiveSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should include correct effectiveDate from phase 2 startDate")
        void shouldIncludeCorrectEffectiveDate() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            long futureEpoch = now + 86400 * 15;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, futureEpoch, STRIPE_PRICE_ID_PRO_MONTHLY,
                    futureEpoch, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            Map<String, String> allPrices = Map.of(
                    "STARTER_monthly", STRIPE_PRICE_ID_STARTER_MONTHLY,
                    "PRO_monthly", STRIPE_PRICE_ID_PRO_MONTHLY
            );
            when(priceCacheService.getAllCachedPrices()).thenReturn(allPrices);
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));

            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getEffectiveDate()).isNotNull();
        }

        @Test
        @DisplayName("should include correct changeType for downgrade")
        void shouldIncludeCorrectChangeTypeForDowngrade() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            Map<String, String> allPrices = Map.of(
                    "STARTER_monthly", STRIPE_PRICE_ID_STARTER_MONTHLY,
                    "PRO_monthly", STRIPE_PRICE_ID_PRO_MONTHLY
            );
            when(priceCacheService.getAllCachedPrices()).thenReturn(allPrices);
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));

            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getChangeType()).isEqualTo("downgrade");
        }

        @Test
        @DisplayName("should set cancellable=true for active schedule")
        void shouldSetCancellableForActiveSchedule() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            Map<String, String> allPrices = Map.of(
                    "STARTER_monthly", STRIPE_PRICE_ID_STARTER_MONTHLY,
                    "PRO_monthly", STRIPE_PRICE_ID_PRO_MONTHLY
            );
            when(priceCacheService.getAllCachedPrices()).thenReturn(allPrices);
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));

            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().isCancellable()).isTrue();
        }
    }

    @Nested
    @DisplayName("cancelScheduledChange")
    class CancelScheduledChange {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
        }

        @Test
        @DisplayName("should successfully cancel scheduled change using release")
        void shouldCancelScheduledChangeUsingRelease() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            SubscriptionSchedule releasedSchedule = mock(SubscriptionSchedule.class);
            lenient().when(releasedSchedule.getId()).thenReturn(STRIPE_SCHEDULE_ID);
            when(subscriptionScheduleService.release(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleReleaseParams.class))).thenReturn(releasedSchedule);

            // Act
            boolean result = stripeScheduleService.cancelScheduledChange(USER_ID);

            // Assert
            assertThat(result).isTrue();
            verify(subscriptionScheduleService).release(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleReleaseParams.class));
        }

        @Test
        @DisplayName("should return false when no scheduled change exists")
        void shouldReturnFalseWhenNoScheduledChange() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            // Act
            boolean result = stripeScheduleService.cancelScheduledChange(USER_ID);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when Stripe throws during release")
        void shouldReturnFalseOnStripeError() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY, STRIPE_SCHEDULE_ID);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            long now = System.currentTimeMillis() / 1000;
            SubscriptionSchedule schedule = buildSchedule(
                    STRIPE_SCHEDULE_ID, "active",
                    now - 86400 * 15, now + 86400 * 15, STRIPE_PRICE_ID_PRO_MONTHLY,
                    now + 86400 * 15, STRIPE_PRICE_ID_STARTER_MONTHLY
            );
            when(subscriptionScheduleService.retrieve(STRIPE_SCHEDULE_ID)).thenReturn(schedule);

            InvalidRequestException stripeEx = new InvalidRequestException(
                    "Schedule cannot be released", null, null, null, 400, null);
            when(subscriptionScheduleService.release(eq(STRIPE_SCHEDULE_ID), any(SubscriptionScheduleReleaseParams.class))).thenThrow(stripeEx);

            // Act
            boolean result = stripeScheduleService.cancelScheduledChange(USER_ID);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when no active subscription exists")
        void shouldReturnFalseWhenNoSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            // Act
            boolean result = stripeScheduleService.cancelScheduledChange(USER_ID);

            // Assert
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("scheduleDowngrade should handle null targetPlanCode gracefully")
        void scheduleDowngradeShouldHandleNullTarget() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // isDowngrade with null returns false, so the method returns error
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(USER_ID, null);

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
        }

        @Test
        @DisplayName("scheduleDowngrade should handle null userId gracefully")
        void scheduleDowngradeShouldHandleNullUserId() {
            when(subscriptionRepository.findActiveByUserId(null)).thenReturn(Optional.empty());

            // Act - should not throw, should return error PlanChangeResult
            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(null, "STARTER");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("scheduleBillingCycleChange should handle unknown cycle value")
        void scheduleBillingCycleChangeShouldHandleUnknownCycle() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(Optional.of("monthly"));
            // Invalid cycle will not be found in cache
            when(priceCacheService.getPriceId("PRO", "biweekly")).thenReturn(Optional.empty());

            // Act
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(USER_ID, "biweekly");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getChangeType()).isEqualTo(PlanChangeResult.ChangeType.ERROR);
        }

        @Test
        @DisplayName("getScheduledChange should return empty for subscription with no provider ID")
        void getScheduledChangeShouldReturnEmptyForNoProviderId() {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, "cus_test123");
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, null, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Act
            Optional<ScheduledChangeInfo> result = stripeScheduleService.getScheduledChange(USER_ID);

            // Assert
            assertThat(result).isEmpty();
        }
    }
}
