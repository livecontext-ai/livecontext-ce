package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.enums.PlanCode;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.util.NonceUtil;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.CustomerService;
import com.stripe.service.PaymentMethodService;
import com.stripe.service.PriceService;
import com.stripe.service.SubscriptionService;
import com.stripe.service.checkout.SessionService;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StripeBillingService.
 *
 * Since StripeBillingService relies heavily on Stripe SDK static-like method calls
 * through StripeClient (SDK 31+), we mock the StripeClient and its service chain.
 * Tests focus on:
 * - Parameter validation and precondition checks
 * - Interaction logic with local repositories
 * - Plan hierarchy (upgrade/downgrade) detection
 * - Error handling paths
 * - Nonce encoding/decoding
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeBillingService Tests")
class StripeBillingServiceTest {

    @Mock
    private StripeClient stripe;

    @Mock
    private BillingCustomerRepository billingCustomerRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceCacheService priceCacheService;

    @Mock
    private PlanCacheService planCacheService;

    @Mock
    private NonceUtil nonceUtil;

    @Mock
    private com.apimarketplace.auth.repository.PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    /**
     * PR-storage-sync (parallel-agent work, 2026-05): StripeBillingService now
     * calls quotaSyncer.syncAfterCommit on plan swap (line 568) + free-downgrade
     * (line 1117). Without this mock, @InjectMocks leaves the field null and
     * every swap path NPEs.
     */
    @Mock
    private PlanStorageQuotaSyncer quotaSyncer;

    // Stripe service mocks (StripeClient returns service objects)
    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private CustomerService customerService;

    @Mock
    private PriceService priceService;

    @Mock
    private CheckoutService checkoutService;

    @Mock
    private SessionService sessionService;

    @Mock
    private PaymentMethodService paymentMethodService;

    @InjectMocks
    private StripeBillingService stripeBillingService;

    // Test data constants
    private static final Long USER_ID = 1L;
    private static final String STRIPE_CUSTOMER_ID = "cus_test123";
    private static final String STRIPE_SUB_ID = "sub_test123";
    private static final String STRIPE_PRICE_ID_STARTER_MONTHLY = "price_starter_monthly";
    private static final String STRIPE_PRICE_ID_PRO_MONTHLY = "price_pro_monthly";
    private static final String NONCE_VALUE = "n_testNonce123";

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

    private com.stripe.model.Subscription buildStripeSubscription(String subId, String status, String priceId) {
        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        lenient().when(stripeSub.getId()).thenReturn(subId);
        lenient().when(stripeSub.getStatus()).thenReturn(status);
        lenient().when(stripeSub.getCancelAtPeriodEnd()).thenReturn(false);
        lenient().when(stripeSub.getSchedule()).thenReturn(null);

        // Build subscription items for SDK 31+
        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(item.getId()).thenReturn("si_test123");
        lenient().when(item.getQuantity()).thenReturn(1L);
        lenient().when(item.getCurrentPeriodStart()).thenReturn(System.currentTimeMillis() / 1000 - 86400 * 15);
        lenient().when(item.getCurrentPeriodEnd()).thenReturn(System.currentTimeMillis() / 1000 + 86400 * 15);

        com.stripe.model.Price stripePrice = mock(com.stripe.model.Price.class);
        lenient().when(stripePrice.getCurrency()).thenReturn("eur");
        lenient().when(stripePrice.getId()).thenReturn(priceId);
        lenient().when(item.getPrice()).thenReturn(stripePrice);

        SubscriptionItemCollection itemCollection = mock(SubscriptionItemCollection.class);
        lenient().when(itemCollection.getData()).thenReturn(List.of(item));
        lenient().when(stripeSub.getItems()).thenReturn(itemCollection);

        return stripeSub;
    }

    private void setupStripeServiceChain() throws StripeException {
        lenient().when(stripe.subscriptions()).thenReturn(subscriptionService);
        lenient().when(stripe.customers()).thenReturn(customerService);
        lenient().when(stripe.prices()).thenReturn(priceService);
        lenient().when(stripe.checkout()).thenReturn(checkoutService);
        lenient().when(checkoutService.sessions()).thenReturn(sessionService);
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
    @DisplayName("createCheckoutSession")
    class CreateCheckoutSession {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should create checkout session for new customer with no existing subscription")
        void shouldCreateCheckoutForNewCustomer() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            // Mock the Stripe customer retrieve to verify customer exists
            Customer stripeCustomer = mock(Customer.class);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/session123");
            when(session.getId()).thenReturn("cs_test123");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert
            assertThat(result).isEqualTo("https://checkout.stripe.com/session123");
            verify(sessionService).create(any(SessionCreateParams.class));
            verify(billingCustomerRepository).findByUserId(USER_ID);
        }

        @Test
        @DisplayName("should create checkout session for existing customer with providerCustomerId")
        void shouldCreateCheckoutForExistingCustomer() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer stripeCustomer = mock(Customer.class);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/session456");
            when(session.getId()).thenReturn("cs_test456");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert
            assertThat(result).isEqualTo("https://checkout.stripe.com/session456");
            verify(customerService).retrieve(STRIPE_CUSTOMER_ID);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for invalid plan code")
        void shouldThrowForInvalidPlanCode() {
            // PlanCode.normalize returns null for invalid input with empty/null
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, null, "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Code de plan invalide");
        }

        @Test
        @DisplayName("should throw exception when user not found")
        void shouldThrowWhenUserNotFound() {
            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Utilisateur non trouve");
        }

        @Test
        @DisplayName("should return null on upgrade when user has active subscription on lower plan")
        void shouldReturnNullOnUpgrade() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            // For the upgrade swap
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(stripeNewPrice);

            // swapPlanAndUpdateLocal internals
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "PRO", "monthly");

            // Assert - null means upgrade was handled in-place, no checkout needed
            assertThat(result).isNull();
            verify(subscriptionService).update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for downgrade attempt")
        void shouldThrowForDowngradeAttempt() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(stripeNewPrice);

            // Act & Assert
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Retrogradation non autorisee");
        }

        @Test
        @DisplayName("should throw when user tries to checkout the same plan")
        void shouldThrowWhenSamePlan() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(stripeNewPrice);

            // Act & Assert
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deja sur le plan");
        }

        @Test
        @DisplayName("should throw for price not found in cache")
        void shouldThrowWhenPriceNotFoundInCache() {
            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Plan non valide");
        }

        @Test
        @DisplayName("should handle canceled Stripe subscription by reconciling and creating new checkout")
        void shouldHandleCanceledStripeSubscription() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Stripe subscription is canceled
            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "canceled", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // After reconciliation, proceeds to checkout
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer stripeCustomer = mock(Customer.class);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/session_new");
            when(session.getId()).thenReturn("cs_new");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "PRO", "monthly");

            // Assert
            assertThat(result).isEqualTo("https://checkout.stripe.com/session_new");
            // Verify local sub was reconciled as canceled
            verify(subscriptionRepository, atLeastOnce()).save(any(Subscription.class));
        }
    }

    @Nested
    @DisplayName("swapPlanAndUpdateLocal")
    class SwapPlanAndUpdateLocal {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should throw when planCode is null")
        void shouldThrowWhenPlanCodeIsNull() {
            assertThatThrownBy(() -> stripeBillingService.swapPlanAndUpdateLocal(USER_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("planCode est requis");
        }

        @Test
        @DisplayName("should throw when planCode is blank")
        void shouldThrowWhenPlanCodeIsBlank() {
            assertThatThrownBy(() -> stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("planCode est requis");
        }

        @Test
        @DisplayName("should throw when no active subscription exists")
        void shouldThrowWhenNoActiveSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "PRO"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Aucun abonnement actif");
        }

        @Test
        @DisplayName("should throw when subscription has no Stripe provider ID")
        void shouldThrowWhenNoProviderSubId() {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, null, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "PRO"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Aucun abonnement Stripe actif");
        }

        @Test
        @DisplayName("should update local subscription after successful Stripe swap")
        void shouldUpdateLocalSubscriptionAfterSwap() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(stripeNewPrice);

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            StripeBillingService.UpgradeResult result = stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "PRO");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getProviderSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
            assertThat(result.getOldPlanCode()).isEqualTo("STARTER");
            assertThat(result.getNewPlanCode()).isEqualTo("PRO");
            verify(subscriptionRepository).save(any(Subscription.class));
            verify(subscriptionService).update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class));
        }

        @Test
        @DisplayName("should preserve billing cycle during plan swap")
        void shouldPreserveBillingCycleDuringSwap() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            String yearlyPriceId = "price_starter_yearly";
            String proYearlyPriceId = "price_pro_yearly";
            Price starterPrice = buildPrice(1L, starterPlan, "yearly", yearlyPriceId);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId(yearlyPriceId)).thenReturn(Optional.of("yearly"));
            when(priceCacheService.getPriceId("PRO", "yearly")).thenReturn(Optional.of(proYearlyPriceId));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", yearlyPriceId);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(proYearlyPriceId)).thenReturn(stripeNewPrice);

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", proYearlyPriceId);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            StripeBillingService.UpgradeResult result = stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "PRO");

            // Assert - verifies that yearly cycle was used (not monthly)
            assertThat(result).isNotNull();
            verify(priceCacheService, atLeast(1)).getPriceId("PRO", "yearly");
        }

        @Test
        @DisplayName("should default to monthly when billing cycle cannot be determined")
        void shouldDefaultToMonthlyWhenCycleUnknown() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            // Price without providerPriceId -> cycle cannot be determined
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", null);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(stripeNewPrice);

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            StripeBillingService.UpgradeResult result = stripeBillingService.swapPlanAndUpdateLocal(USER_ID, "PRO");

            // Assert - defaults to monthly
            assertThat(result).isNotNull();
            verify(priceCacheService, atLeast(1)).getPriceId("PRO", "monthly");
        }
    }

    @Nested
    @DisplayName("Plan Hierarchy Detection")
    class PlanHierarchyDetection {

        /*
         * planRank and isUpgrade/isDowngrade are private methods.
         * We test their behavior indirectly through createCheckoutSession/swapPlanAndUpdateLocal.
         * We also test PlanCacheService interaction to verify the hierarchy.
         */

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("FREE to STARTER is detected as upgrade")
        void freeToStarterIsUpgrade() throws Exception {
            // Arrange - user on FREE plan, checkout for STARTER should trigger upgrade path
            User user = buildUser(USER_ID, "test@example.com");
            Plan freePlan = buildPlan(1L, "FREE", "Free");
            Price freePrice = buildPrice(1L, freePlan, "monthly", "price_free");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, freePlan, freePrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId("price_free")).thenReturn(Optional.of("monthly"));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", "price_free");
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(stripeNewPrice);

            // Setup for swapPlanAndUpdateLocal
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act - should trigger upgrade path (returns null = upgrade handled in place)
            String result = stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert
            assertThat(result).isNull(); // upgrade handled via swap, no checkout URL
            verify(subscriptionService).update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class));
        }

        @Test
        @DisplayName("STARTER to PRO is detected as upgrade")
        void starterToProIsUpgrade() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(stripeNewPrice);

            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            when(priceCacheService.getBillingCycleByPriceId(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(Optional.of("monthly"));
            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "PRO", "monthly");

            // Assert
            assertThat(result).isNull(); // upgrade handled in-place
        }

        @Test
        @DisplayName("PRO to STARTER is detected as downgrade and throws")
        void proToStarterIsDowngrade() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Price proPrice = buildPrice(1L, proPlan, "monthly", STRIPE_PRICE_ID_PRO_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, proPlan, proPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_PRO_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(STRIPE_PRICE_ID_STARTER_MONTHLY)).thenReturn(stripeNewPrice);

            // Act & Assert
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Retrogradation");
        }

        @Test
        @DisplayName("ENTERPRISE_BASIC to FREE is detected as downgrade and throws")
        void enterpriseToFreeIsDowngrade() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            Plan entPlan = buildPlan(4L, "ENTERPRISE_BASIC", "Enterprise Basic");
            Price entPrice = buildPrice(1L, entPlan, "monthly", "price_ent_monthly");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, entPlan, entPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("FREE", "monthly")).thenReturn(Optional.of("price_free"));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", "price_ent_monthly");
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve("price_free")).thenReturn(stripeNewPrice);

            // Act & Assert
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "FREE", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Retrogradation");
        }

        @Test
        @DisplayName("FREE to ENTERPRISE_BASIC is detected as upgrade")
        void freeToEnterpriseIsUpgrade() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            Plan freePlan = buildPlan(1L, "FREE", "Free");
            Price freePrice = buildPrice(1L, freePlan, "monthly", "price_free");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, freePlan, freePrice, STRIPE_SUB_ID, "active");

            String entPriceId = "price_ent_monthly";
            when(priceCacheService.getPriceId("ENTERPRISE_BASIC", "monthly")).thenReturn(Optional.of(entPriceId));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));
            when(priceCacheService.getBillingCycleByPriceId("price_free")).thenReturn(Optional.of("monthly"));

            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", "price_free");
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("eur");
            when(priceService.retrieve(entPriceId)).thenReturn(stripeNewPrice);

            Plan entPlan = buildPlan(4L, "ENTERPRISE_BASIC", "Enterprise Basic");
            when(planRepository.findByCode("ENTERPRISE_BASIC")).thenReturn(Optional.of(entPlan));

            com.stripe.model.Subscription updatedStripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", entPriceId);
            when(subscriptionService.update(eq(STRIPE_SUB_ID), any(SubscriptionUpdateParams.class))).thenReturn(updatedStripeSub);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "ENTERPRISE_BASIC", "monthly");

            // Assert
            assertThat(result).isNull(); // upgrade handled in-place
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        @Test
        @DisplayName("should handle InvalidRequestException for no such subscription during checkout")
        void shouldHandleNoSuchSubscriptionDuringCheckout() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Stripe says subscription doesn't exist
            InvalidRequestException noSuchSubEx = new InvalidRequestException(
                    "No such subscription: " + STRIPE_SUB_ID, null, "resource_missing", null, 404, null);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenThrow(noSuchSubEx);
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(localSub);

            // After reconciliation, should create a checkout
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer stripeCustomer = mock(Customer.class);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/recovery");
            when(session.getId()).thenReturn("cs_recovery");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "PRO", "monthly");

            // Assert
            assertThat(result).isEqualTo("https://checkout.stripe.com/recovery");
        }

        @Test
        @DisplayName("should handle missing BillingCustomer by creating a new one")
        void shouldHandleMissingBillingCustomer() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            // No existing billing customer -> ensureValidStripeCustomer creates one
            BillingCustomer newBc = new BillingCustomer(user, "stripe");
            newBc.setId(1L);
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer createdCustomer = mock(Customer.class);
            when(createdCustomer.getId()).thenReturn("cus_new123");
            when(customerService.create(any(CustomerCreateParams.class))).thenReturn(createdCustomer);

            newBc.setProviderCustomerId("cus_new123");
            when(billingCustomerRepository.save(any(BillingCustomer.class))).thenReturn(newBc);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/new");
            when(session.getId()).thenReturn("cs_new");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            String result = stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert
            assertThat(result).isEqualTo("https://checkout.stripe.com/new");
            verify(customerService).create(any(CustomerCreateParams.class));
            verify(billingCustomerRepository).save(any(BillingCustomer.class));
        }

        @Test
        @DisplayName("should handle null planCode by throwing exception")
        void shouldHandleNullPlanCode() {
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, null, "monthly"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should handle currency mismatch by throwing exception")
        void shouldHandleCurrencyMismatch() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Price starterPrice = buildPrice(1L, starterPlan, "monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);
            Subscription localSub = buildSubscription(1L, bc, starterPlan, starterPrice, STRIPE_SUB_ID, "active");

            when(priceCacheService.getPriceId("PRO", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(localSub));

            // Current subscription is in EUR
            com.stripe.model.Subscription stripeSub = buildStripeSubscription(STRIPE_SUB_ID, "active", STRIPE_PRICE_ID_STARTER_MONTHLY);
            when(subscriptionService.retrieve(STRIPE_SUB_ID)).thenReturn(stripeSub);

            // Target price is in USD
            com.stripe.model.Price stripeNewPrice = mock(com.stripe.model.Price.class);
            when(stripeNewPrice.getCurrency()).thenReturn("usd");
            when(priceService.retrieve(STRIPE_PRICE_ID_PRO_MONTHLY)).thenReturn(stripeNewPrice);

            // Act & Assert
            assertThatThrownBy(() -> stripeBillingService.createCheckoutSession(USER_ID, "PRO", "monthly"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("devise");
        }
    }

    @Nested
    @DisplayName("decodeUserIdFromNonce")
    class DecodeUserIdFromNonce {

        @Test
        @DisplayName("should return userId for valid nonce")
        void shouldReturnUserIdForValidNonce() {
            when(nonceUtil.decodeNonce(NONCE_VALUE)).thenReturn(USER_ID);

            Long result = stripeBillingService.decodeUserIdFromNonce(NONCE_VALUE);

            assertThat(result).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("should return null for null nonce")
        void shouldReturnNullForNullNonce() {
            Long result = stripeBillingService.decodeUserIdFromNonce(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for empty nonce")
        void shouldReturnNullForEmptyNonce() {
            Long result = stripeBillingService.decodeUserIdFromNonce("");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for invalid nonce")
        void shouldReturnNullForInvalidNonce() {
            when(nonceUtil.decodeNonce("invalid_nonce")).thenReturn(null);

            Long result = stripeBillingService.decodeUserIdFromNonce("invalid_nonce");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("validateNonceForUser")
    class ValidateNonceForUser {

        @Test
        @DisplayName("should return true when nonce matches user")
        void shouldReturnTrueWhenNonceMatches() {
            when(nonceUtil.validateNonce(NONCE_VALUE, USER_ID)).thenReturn(true);

            boolean result = stripeBillingService.validateNonceForUser(NONCE_VALUE, USER_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when nonce does not match user")
        void shouldReturnFalseWhenNonceMismatch() {
            when(nonceUtil.validateNonce(NONCE_VALUE, 999L)).thenReturn(false);

            boolean result = stripeBillingService.validateNonceForUser(NONCE_VALUE, 999L);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getDebugInfo")
    class GetDebugInfo {

        @Test
        @DisplayName("should return debug info with cached prices")
        void shouldReturnDebugInfo() {
            java.util.Map<String, String> prices = new java.util.HashMap<>();
            prices.put("STARTER_monthly", STRIPE_PRICE_ID_STARTER_MONTHLY);
            prices.put("PRO_monthly", STRIPE_PRICE_ID_PRO_MONTHLY);

            when(priceCacheService.getAllCachedPrices()).thenReturn(prices);
            when(priceCacheService.getPriceId("FREE")).thenReturn(Optional.of("price_free"));
            when(priceCacheService.getPriceId("STARTER")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(priceCacheService.getPriceId("PRO")).thenReturn(Optional.of(STRIPE_PRICE_ID_PRO_MONTHLY));
            when(priceCacheService.getPriceId("ENTERPRISE_BASIC")).thenReturn(Optional.empty());
            when(priceCacheService.getPriceId("ENTERPRISE_STANDARD")).thenReturn(Optional.empty());
            when(priceCacheService.getPriceId("ENTERPRISE_PREMIUM")).thenReturn(Optional.empty());
            when(priceCacheService.getPriceId("ENTERPRISE_ULTIMATE")).thenReturn(Optional.empty());
            when(priceCacheService.getPriceId("PAYG")).thenReturn(Optional.empty());

            java.util.Map<String, Object> debugInfo = stripeBillingService.getDebugInfo();

            assertThat(debugInfo).isNotNull();
            assertThat(debugInfo).containsKey("cachedPrices");
            assertThat(debugInfo).containsKey("cachedPricesSize");
            assertThat((int) debugInfo.get("cachedPricesSize")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Automatic tax on Checkout sessions")
    class AutomaticTaxOnCheckoutSessions {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
            setupPlanHierarchy();
        }

        // Regression: Stripe Tax was registered (FR) but live Checkout sessions were
        // created without automatic_tax → 6/6 sessions billed without tax calculation.

        @Test
        @DisplayName("subscription checkout session enables automatic_tax and keeps customer_update.address=auto")
        void subscriptionCheckoutEnablesAutomaticTax() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(mock(Customer.class));

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/tax");
            when(session.getId()).thenReturn("cs_tax");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert
            ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(sessionService).create(captor.capture());
            SessionCreateParams params = captor.getValue();

            assertThat(params.getAutomaticTax())
                    .as("automatic_tax must be set on subscription checkout sessions")
                    .isNotNull();
            assertThat(params.getAutomaticTax().getEnabled()).isTrue();
            assertThat(params.getCustomerUpdate())
                    .as("automatic_tax with an existing customer requires customer_update.address=auto")
                    .isNotNull();
            assertThat(params.getCustomerUpdate().getAddress())
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Address.AUTO);
            assertThat(params.getTaxIdCollection())
                    .as("tax_id_collection must be enabled for EU B2B reverse charge")
                    .isNotNull();
            assertThat(params.getTaxIdCollection().getEnabled()).isTrue();
            assertThat(params.getCustomerUpdate().getName())
                    .as("tax_id_collection with an existing customer requires customer_update.name=auto")
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Name.AUTO);
        }

        @Test
        @DisplayName("checkout retry after no-such-customer rebuilds the session with automatic_tax still enabled")
        void retryCheckoutKeepsAutomaticTax() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(mock(Customer.class));

            // recreateStripeCustomer path after the first create() rejects the customer
            Customer recreated = mock(Customer.class);
            when(recreated.getId()).thenReturn("cus_recreated");
            when(customerService.create(any(CustomerCreateParams.class))).thenReturn(recreated);
            when(billingCustomerRepository.save(any(BillingCustomer.class))).thenAnswer(inv -> inv.getArgument(0));

            InvalidRequestException noSuchCustomer = new InvalidRequestException(
                    "No such customer: " + STRIPE_CUSTOMER_ID, null, "resource_missing", null, 404, null);
            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/retry");
            when(session.getId()).thenReturn("cs_retry");
            when(sessionService.create(any(SessionCreateParams.class)))
                    .thenThrow(noSuchCustomer)
                    .thenReturn(session);

            // Act
            stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert - the RETRY session (second create call) must keep automatic_tax,
            // locking the invariant against a future inline rebuild of the retry params
            ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(sessionService, times(2)).create(captor.capture());
            SessionCreateParams retryParams = captor.getAllValues().get(1);

            assertThat(retryParams.getAutomaticTax())
                    .as("automatic_tax must survive the no-such-customer retry rebuild")
                    .isNotNull();
            assertThat(retryParams.getAutomaticTax().getEnabled()).isTrue();
            assertThat(retryParams.getCustomerUpdate()).isNotNull();
            assertThat(retryParams.getCustomerUpdate().getAddress())
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Address.AUTO);
            assertThat(retryParams.getTaxIdCollection())
                    .as("tax_id_collection must survive the no-such-customer retry rebuild")
                    .isNotNull();
            assertThat(retryParams.getTaxIdCollection().getEnabled()).isTrue();
            assertThat(retryParams.getCustomerUpdate().getName())
                    .as("customer_update.name=auto must survive the retry rebuild")
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Name.AUTO);
        }

        @Test
        @DisplayName("customer sync preserves an existing Stripe customer name (set by tax_id_collection)")
        void customerSyncPreservesExistingName() throws Exception {
            // Arrange - Stripe customer already carries a business name saved at checkout
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer stripeCustomer = mock(Customer.class);
            when(stripeCustomer.getName()).thenReturn("ACME SAS");
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/keepname");
            when(session.getId()).thenReturn("cs_keepname");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert - the sync update must NOT carry a name (it would clobber "ACME SAS")
            ArgumentCaptor<com.stripe.param.CustomerUpdateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.CustomerUpdateParams.class);
            verify(customerService).update(eq(STRIPE_CUSTOMER_ID), captor.capture());
            assertThat(captor.getValue().getName())
                    .as("an existing Stripe customer name must be preserved, not re-stamped")
                    .isNull();
        }

        @Test
        @DisplayName("customer sync stamps the user name when the Stripe customer has none")
        void customerSyncStampsNameWhenEmpty() throws Exception {
            // Arrange - Stripe customer has no name yet
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("STARTER", "monthly")).thenReturn(Optional.of(STRIPE_PRICE_ID_STARTER_MONTHLY));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);

            Customer stripeCustomer = mock(Customer.class);
            when(stripeCustomer.getName()).thenReturn(null);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(stripeCustomer);

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/stampname");
            when(session.getId()).thenReturn("cs_stampname");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            stripeBillingService.createCheckoutSession(USER_ID, "STARTER", "monthly");

            // Assert - nameless customer gets the user's name stamped
            ArgumentCaptor<com.stripe.param.CustomerUpdateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.CustomerUpdateParams.class);
            verify(customerService).update(eq(STRIPE_CUSTOMER_ID), captor.capture());
            assertThat(captor.getValue().getName())
                    .as("a nameless Stripe customer must get the user's name")
                    .isNotNull();
        }

        @Test
        @DisplayName("PAYG top-up checkout session enables automatic_tax with customer_update.address=auto")
        void paygCheckoutEnablesAutomaticTax() throws Exception {
            // Arrange
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(1L, user, STRIPE_CUSTOMER_ID);

            when(priceCacheService.getPriceId("PAYG", "payg_small")).thenReturn(Optional.of("price_payg_small"));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
            when(nonceUtil.generateNonce(USER_ID)).thenReturn(NONCE_VALUE);
            when(customerService.retrieve(STRIPE_CUSTOMER_ID)).thenReturn(mock(Customer.class));

            Session session = mock(Session.class);
            when(session.getUrl()).thenReturn("https://checkout.stripe.com/payg");
            when(session.getId()).thenReturn("cs_payg");
            when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

            // Act
            stripeBillingService.createPaygCheckoutSession(USER_ID, "small");

            // Assert
            ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(sessionService).create(captor.capture());
            SessionCreateParams params = captor.getValue();

            assertThat(params.getAutomaticTax())
                    .as("automatic_tax must be set on PAYG one-time checkout sessions")
                    .isNotNull();
            assertThat(params.getAutomaticTax().getEnabled()).isTrue();
            assertThat(params.getCustomerUpdate())
                    .as("automatic_tax with an existing customer requires customer_update.address=auto")
                    .isNotNull();
            assertThat(params.getCustomerUpdate().getAddress())
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Address.AUTO);
            assertThat(params.getTaxIdCollection())
                    .as("tax_id_collection must be enabled for EU B2B reverse charge")
                    .isNotNull();
            assertThat(params.getTaxIdCollection().getEnabled()).isTrue();
            assertThat(params.getCustomerUpdate().getName())
                    .as("tax_id_collection with an existing customer requires customer_update.name=auto")
                    .isEqualTo(SessionCreateParams.CustomerUpdate.Name.AUTO);
            assertThat(params.getInvoiceCreation())
                    .as("payment-mode sessions need invoice_creation so B2B top-ups get a reverse-charge invoice")
                    .isNotNull();
            assertThat(params.getInvoiceCreation().getEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("createBillingPortalSession")
    class CreateBillingPortalSession {

        @BeforeEach
        void setUp() throws StripeException {
            setupStripeServiceChain();
        }

        @Test
        @DisplayName("should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stripeBillingService.createBillingPortalSession(USER_ID, "http://localhost:3000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found");
        }
    }

    // ========================================================================
    // V250 two-bucket reconcile guard - H1 regression: a Stripe-side cancel
    // (admin Dashboard / "no such subscription") must not strand the user's
    // PAYG balance on an unreachable canceled row. Mirror of the
    // onSubscriptionUpsert sibling-cancel carry-over fix.
    // ========================================================================

    @Nested
    @DisplayName("reconcileLocalCanceled - V250 PAYG carry-over")
    class ReconcileLocalCanceledPaygCarry {

        @Test
        @DisplayName("V250 regression: canceled sub with positive PAYG balance carries to active sibling - without this, paid PAYG dollars are stranded on the canceled row that findActiveByUserId never returns")
        void carriesPaygBalanceToActiveSiblingOnCancel() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(10L, user, STRIPE_CUSTOMER_ID);
            Plan plan = buildPlan(2L, "STARTER", "Starter");

            // Canceled-target sub holds $50 PAYG that must NOT vanish.
            Subscription canceledLocal = buildSubscription(100L, bc, plan, null, "sub_old", "active");
            canceledLocal.setPaygRemainingCredits(new java.math.BigDecimal("50"));
            canceledLocal.setRemainingCredits(new java.math.BigDecimal("0"));

            // Active sibling that should pick up the carry-over.
            Subscription activeSibling = buildSubscription(101L, bc, plan, null, "sub_new", "active");
            activeSibling.setPaygRemainingCredits(new java.math.BigDecimal("10"));

            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(activeSibling));
            when(subscriptionRepository.save(any(Subscription.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "reconcileLocalCanceled", canceledLocal, "Stripe status=canceled");

            assertThat(canceledLocal.getStatus()).isEqualTo("canceled");
            assertThat(canceledLocal.getPaygRemainingCredits()).isEqualByComparingTo("0");
            assertThat(activeSibling.getPaygRemainingCredits()).isEqualByComparingTo("60");  // 10 + 50
        }

        @Test
        @DisplayName("V250 regression: when no active sibling exists, PAYG balance is kept on the canceled row + WARN logged - never silently wiped")
        void keepsPaygOnCanceledRowWhenNoSibling() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(10L, user, STRIPE_CUSTOMER_ID);
            Plan plan = buildPlan(2L, "STARTER", "Starter");

            Subscription canceledLocal = buildSubscription(100L, bc, plan, null, "sub_old", "active");
            canceledLocal.setPaygRemainingCredits(new java.math.BigDecimal("25"));

            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.empty());
            when(subscriptionRepository.save(any(Subscription.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "reconcileLocalCanceled", canceledLocal, "Stripe says: no such subscription");

            assertThat(canceledLocal.getStatus()).isEqualTo("canceled");
            // PAYG balance preserved for ops to manually reconcile (refund / migrate).
            assertThat(canceledLocal.getPaygRemainingCredits()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("Zero-PAYG canceled subs take the fast path - no findActiveByUserId lookup, original behavior preserved")
        void zeroPaygSkipsCarryLookup() throws Exception {
            User user = buildUser(USER_ID, "test@example.com");
            BillingCustomer bc = buildBillingCustomer(10L, user, STRIPE_CUSTOMER_ID);
            Plan plan = buildPlan(2L, "STARTER", "Starter");

            Subscription canceledLocal = buildSubscription(100L, bc, plan, null, "sub_old", "active");
            canceledLocal.setPaygRemainingCredits(java.math.BigDecimal.ZERO);

            when(subscriptionRepository.save(any(Subscription.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "reconcileLocalCanceled", canceledLocal, "no-payg-path");

            assertThat(canceledLocal.getStatus()).isEqualTo("canceled");
            assertThat(canceledLocal.getProviderSubscriptionId()).isNull();
            // No lookup needed when PAYG is zero - verify by absence of any sibling stub interaction.
            org.mockito.Mockito.verify(subscriptionRepository, org.mockito.Mockito.never())
                    .findActiveByUserId(any());
        }
    }

    // ========================================================================
    // verifyPaymentMethodAttached - private method exercised via reflection.
    // Validates PM-is-attached-to-customer guard used by upgradeCreditTierImmediate.
    // ========================================================================

    @Nested
    @DisplayName("verifyPaymentMethodAttached")
    class VerifyPaymentMethodAttached {

        @Test
        @DisplayName("Returns pmId when payment method is attached to the expected customer")
        void returnsIdWhenPaymentMethodAttachedToCustomer() throws Exception {
            // Arrange
            String pmId = "pm_attached_123";
            var pm = mock(com.stripe.model.PaymentMethod.class);
            when(pm.getCustomer()).thenReturn(STRIPE_CUSTOMER_ID);
            when(stripe.paymentMethods()).thenReturn(paymentMethodService);
            when(paymentMethodService.retrieve(pmId)).thenReturn(pm);

            // Act
            String result = ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "verifyPaymentMethodAttached", pmId, STRIPE_CUSTOMER_ID);

            // Assert
            assertThat(result).isEqualTo(pmId);
        }

        @Test
        @DisplayName("Returns null when payment method is attached to a different customer")
        void returnsNullWhenPaymentMethodAttachedToDifferentCustomer() throws Exception {
            // Arrange
            String pmId = "pm_detached_456";
            var pm = mock(com.stripe.model.PaymentMethod.class);
            when(pm.getCustomer()).thenReturn("cus_someone_else");
            when(stripe.paymentMethods()).thenReturn(paymentMethodService);
            when(paymentMethodService.retrieve(pmId)).thenReturn(pm);

            // Act
            String result = ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "verifyPaymentMethodAttached", pmId, STRIPE_CUSTOMER_ID);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null when Stripe throws an exception during PM retrieval")
        void returnsNullWhenStripeThrows() throws Exception {
            // Arrange
            String pmId = "pm_error_789";
            when(stripe.paymentMethods()).thenReturn(paymentMethodService);
            when(paymentMethodService.retrieve(pmId)).thenThrow(
                    new InvalidRequestException(
                            "No such payment method: " + pmId, null, "resource_missing", null, 404, null));

            // Act
            String result = ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "verifyPaymentMethodAttached", pmId, STRIPE_CUSTOMER_ID);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null when pmId is null - short-circuits without calling Stripe")
        void returnsNullWhenPmIdIsNull() {
            // Act
            String result = ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "verifyPaymentMethodAttached",
                    new Object[]{null, STRIPE_CUSTOMER_ID});

            // Assert
            assertThat(result).isNull();
            // Stripe should never be called for null pmId
            verifyNoInteractions(paymentMethodService);
        }

        @Test
        @DisplayName("Returns null when pmId is blank - short-circuits without calling Stripe")
        void returnsNullWhenPmIdIsBlank() {
            // Act
            String result = ReflectionTestUtils.invokeMethod(
                    stripeBillingService, "verifyPaymentMethodAttached", "   ", STRIPE_CUSTOMER_ID);

            // Assert
            assertThat(result).isNull();
            verifyNoInteractions(paymentMethodService);
        }
    }
}
