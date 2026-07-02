package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.dto.PlanChangeResult;
import com.apimarketplace.auth.domain.dto.ScheduledChangeInfo;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanCacheService;
import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.service.StripeScheduleService;
import com.apimarketplace.auth.service.SubscriptionService;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.StripeCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.InvoiceListParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.InvoiceService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillingController Integration Tests")
class BillingControllerIntegrationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private StripeBillingService stripeBillingService;
    @Mock private UserRepository userRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PriceRepository priceRepository;
    @Mock private BillingEventRepository billingEventRepository;
    @Mock private PlanCacheService planCacheService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PriceCacheService priceCacheService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private NonceUtil nonceUtil;
    @Mock private StripeScheduleService stripeScheduleService;
    @Mock private StripeClient stripeClient;

    private BillingController billingController;

    private static final Long USER_ID = 1L;
    private static final String USER_ID_STR = "1";

    @BeforeEach
    void setUp() {
        billingController = new BillingController();

        // Inject mocks via reflection (controller uses @Autowired field injection)
        ReflectionTestUtils.setField(billingController, "stripeBillingService", stripeBillingService);
        ReflectionTestUtils.setField(billingController, "userRepository", userRepository);
        ReflectionTestUtils.setField(billingController, "planRepository", planRepository);
        ReflectionTestUtils.setField(billingController, "priceRepository", priceRepository);
        ReflectionTestUtils.setField(billingController, "billingEventRepository", billingEventRepository);
        ReflectionTestUtils.setField(billingController, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(billingController, "planCacheService", planCacheService);
        ReflectionTestUtils.setField(billingController, "subscriptionRepository", subscriptionRepository);
        ReflectionTestUtils.setField(billingController, "billingCustomerRepository", billingCustomerRepository);
        ReflectionTestUtils.setField(billingController, "priceCacheService", priceCacheService);
        ReflectionTestUtils.setField(billingController, "subscriptionService", subscriptionService);
        ReflectionTestUtils.setField(billingController, "nonceUtil", nonceUtil);
        ReflectionTestUtils.setField(billingController, "stripeScheduleService", stripeScheduleService);
        ReflectionTestUtils.setField(billingController, "stripeClient", stripeClient);

        // Milestone-1 audit fix: PR5 added requireActiveOrgOwner guard on
        // all 9 billing mutation endpoints. Integration tests in this class
        // construct requests without X-Organization-Role - without OWNER,
        // every write returns 403 NOT_WORKSPACE_OWNER and the assertions
        // checking 200/4xx fail. Inject the header by default; individual
        // tests that want to verify the 403 path (none today - covered
        // by BillingControllerOwnerGuardTest) can override with .header().
        mockMvc = MockMvcBuilders.standaloneSetup(billingController)
                .defaultRequest(post("/").header("X-Organization-Role", "OWNER"))
                .build();
    }

    // ======================== HELPER METHODS ========================

    private Plan buildPlan(Long id, String code, String name) {
        Plan plan = new Plan(code, name, "Description of " + name);
        plan.setId(id);
        plan.setIncludedToolCredits(100L);
        plan.setIncludedStorageBytes(1073741824L); // 1 GB
        plan.setPaygPricePerToolCredit(10L);
        return plan;
    }

    private Price buildPrice(Long id, Plan plan, String cadence, int amountCents) {
        Price price = new Price(plan, cadence, amountCents);
        price.setId(id);
        price.setProviderPriceId("price_test_" + plan.getCode().toLowerCase() + "_" + cadence);
        return price;
    }

    private User buildUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@test.com");
        user.setUsername("testuser" + id);
        return user;
    }

    private BillingCustomer buildBillingCustomer(Long id, User user) {
        BillingCustomer bc = new BillingCustomer(user, "stripe");
        bc.setId(id);
        bc.setProviderCustomerId("cus_test_" + id);
        return bc;
    }

    private Subscription buildSubscription(Long id, Plan plan, BillingCustomer bc, String status) {
        Subscription sub = new Subscription();
        sub.setId(id);
        sub.setPlan(plan);
        sub.setBillingCustomer(bc);
        sub.setStatus(status);
        sub.setProvider("stripe");
        sub.setProviderSubscriptionId("sub_test_" + id);
        sub.setCadence("monthly");
        sub.setQuantity(1);
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        sub.setCancelAtPeriodEnd(false);
        sub.setCreatedAt(LocalDateTime.now().minusDays(15));
        sub.setUpdatedAt(LocalDateTime.now());
        return sub;
    }

    private Subscription buildSubscriptionWithPrice(Long id, Plan plan, Price price, BillingCustomer bc, String status) {
        Subscription sub = buildSubscription(id, plan, bc, status);
        sub.setPrice(price);
        sub.setCadence(price.getCadence());
        return sub;
    }

    private Map<String, Plan> buildAllPlansMap() {
        Map<String, Plan> plans = new LinkedHashMap<>();
        plans.put("FREE", buildPlan(1L, "FREE", "Free"));
        plans.put("STARTER", buildPlan(2L, "STARTER", "Starter"));
        plans.put("PRO", buildPlan(3L, "PRO", "Pro"));
        plans.put("ENTERPRISE_BASIC", buildPlan(4L, "ENTERPRISE_BASIC", "Enterprise Basic"));
        plans.put("ENTERPRISE_STANDARD", buildPlan(5L, "ENTERPRISE_STANDARD", "Enterprise Standard"));
        plans.put("ENTERPRISE_PREMIUM", buildPlan(6L, "ENTERPRISE_PREMIUM", "Enterprise Premium"));
        plans.put("ENTERPRISE_ULTIMATE", buildPlan(7L, "ENTERPRISE_ULTIMATE", "Enterprise Ultimate"));
        plans.put("PAYG", buildPlan(8L, "PAYG", "Pay As You Go"));
        return plans;
    }

    private Map<String, Price> buildAllPricesMap() {
        Map<String, Plan> plans = buildAllPlansMap();
        Map<String, Price> prices = new LinkedHashMap<>();
        prices.put("FREE_monthly", buildPrice(1L, plans.get("FREE"), "monthly", 0));
        prices.put("STARTER_monthly", buildPrice(2L, plans.get("STARTER"), "monthly", 1900));
        prices.put("STARTER_yearly", buildPrice(3L, plans.get("STARTER"), "yearly", 19000));
        prices.put("PRO_monthly", buildPrice(4L, plans.get("PRO"), "monthly", 4900));
        prices.put("PRO_yearly", buildPrice(5L, plans.get("PRO"), "yearly", 49000));
        prices.put("ENTERPRISE_BASIC_monthly", buildPrice(6L, plans.get("ENTERPRISE_BASIC"), "monthly", 9900));
        prices.put("ENTERPRISE_BASIC_yearly", buildPrice(7L, plans.get("ENTERPRISE_BASIC"), "yearly", 99000));
        return prices;
    }

    private ScheduledChangeInfo buildScheduledChangeInfo() {
        return ScheduledChangeInfo.builder()
                .scheduleId("sub_sched_123")
                .currentPlanCode("PRO")
                .currentPlanName("Pro")
                .targetPlanCode("STARTER")
                .targetPlanName("Starter")
                .effectiveDate(LocalDateTime.now().plusDays(15))
                .changeType("downgrade")
                .currentBillingCycle("monthly")
                .targetBillingCycle("monthly")
                .status("active")
                .cancellable(true)
                .userMessage("Your plan will switch to Starter on 2026-03-01")
                .build();
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    // ======================== POST /api/billing/checkout ========================

    @Nested
    @DisplayName("POST /api/billing/checkout")
    class CreateCheckoutSession {

        @Test
        @DisplayName("should create checkout for STARTER plan monthly and return Stripe URL")
        void checkoutStarterMonthly() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("STARTER"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_starter_monthly");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_test_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER", "billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_starter_monthly"))
                    .andExpect(jsonPath("$.planCode").value("STARTER"))
                    .andExpect(jsonPath("$.isFreePlan").value(false));

            verify(stripeBillingService).createCheckoutSession(USER_ID, "STARTER", "monthly", 0);
        }

        @Test
        @DisplayName("should create checkout for PRO plan yearly and return Stripe URL")
        void checkoutProYearly() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("yearly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_pro_yearly");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_test_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "PRO", "billingCycle", "yearly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_pro_yearly"))
                    .andExpect(jsonPath("$.planCode").value("PRO"));

            verify(stripeBillingService).createCheckoutSession(USER_ID, "PRO", "yearly", 0);
        }

        @Test
        @DisplayName("should handle FREE plan checkout without Stripe session")
        void checkoutFreePlan() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            User user = buildUser(USER_ID);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "FREE"))))
                    .andExpect(status().isOk());

            verify(stripeBillingService, never()).createCheckoutSession(anyLong(), anyString(), anyString(), anyInt());
            verify(billingEventRepository).save(any());
        }

        @Test
        @DisplayName("should return 400 for invalid plan code")
        void checkoutInvalidPlanCode() throws Exception {
            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", ""))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("planCode est requis"));
        }

        @Test
        @DisplayName("should return 400 when planCode is missing")
        void checkoutMissingPlanCode() throws Exception {
            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("planCode est requis"));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void checkoutWithoutUserHeader() throws Exception {
            mockMvc.perform(post("/api/billing/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("should create checkout for ENTERPRISE plan monthly")
        void checkoutEnterpriseMonthly() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            // PlanCode.normalize("ENTERPRISE") returns "ENTERPRISE_BASIC"
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("ENTERPRISE_BASIC"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_enterprise");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_ent_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "ENTERPRISE", "billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_enterprise"));
        }

        @Test
        @DisplayName("should return 500 when Stripe throws exception")
        void checkoutStripeException() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("STARTER"), eq("monthly"), eq(0)))
                    .thenThrow(new RuntimeException("Stripe connection error"));

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER", "billingCycle", "monthly"))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("Erreur interne du serveur"));
        }

        @Test
        @DisplayName("propagates StripeException to @ControllerAdvice so resource_missing maps to 404 (regression: BillingController catch(Exception) used to mask Stripe errors as 500)")
        void checkoutStripeResourceMissingPropagatesAs404() throws Exception {
            // Wire StripeExceptionHandler so the @ControllerAdvice path is exercised.
            MockMvc mvcWithAdvice = MockMvcBuilders.standaloneSetup(billingController)
                    .setControllerAdvice(new StripeExceptionHandler())
                    .defaultRequest(post("/").header("X-Organization-Role", "OWNER"))
                    .build();

            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);

            InvalidRequestException stripeError = new InvalidRequestException(
                    "No such price: 'price_xyz'", null, "req_test_123",
                    "resource_missing", 404, null);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenThrow(stripeError);

            mvcWithAdvice.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "PRO", "billingCycle", "monthly"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }

        @Test
        @DisplayName("should handle upgrade scenario where checkout returns null URL")
        void checkoutUpgradeReturnsNullUrl() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenReturn(null);
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_upgrade_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "PRO", "billingCycle", "monthly"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should default billingCycle to monthly when not provided")
        void checkoutDefaultBillingCycle() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("STARTER"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_default");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_default_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER"))))
                    .andExpect(status().isOk());

            verify(stripeBillingService).createCheckoutSession(USER_ID, "STARTER", "monthly", 0);
        }

        @Test
        @DisplayName("should return 400 for plan code not in available plans")
        void checkoutUnavailablePlan() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "PLATINUM"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("Plan non supporte")));
        }

        @Test
        @DisplayName("should return 401 when X-User-ID is non-numeric")
        void checkoutInvalidUserIdFormat() throws Exception {
            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", "not-a-number")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 400 when Stripe plan is not configured")
        void checkoutStripePlanNotConfigured() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("STARTER"), eq("monthly"), eq(0)))
                    .thenThrow(new IllegalArgumentException("Plan non valide: STARTER"));

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "STARTER"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("Plan non configure dans Stripe")));
        }
    }

    // ======================== POST /api/billing/portal ========================

    @Nested
    @DisplayName("POST /api/billing/portal")
    class CreatePortalSession {

        @Test
        @DisplayName("should create portal session and return URL")
        void portalSessionCreation() throws Exception {
            when(stripeBillingService.createBillingPortalSession(eq(USER_ID), anyString()))
                    .thenReturn("https://billing.stripe.com/portal_session");

            mockMvc.perform(post("/api/billing/portal")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("returnUrl", "https://myapp.com/settings"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://billing.stripe.com/portal_session"));

            verify(stripeBillingService).createBillingPortalSession(USER_ID, "https://myapp.com/settings");
        }

        @Test
        @DisplayName("should create portal session with custom returnUrl")
        void portalSessionWithCustomReturnUrl() throws Exception {
            String customUrl = "https://myapp.com/custom-return";
            when(stripeBillingService.createBillingPortalSession(USER_ID, customUrl))
                    .thenReturn("https://billing.stripe.com/portal_custom");

            mockMvc.perform(post("/api/billing/portal")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("returnUrl", customUrl))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://billing.stripe.com/portal_custom"));

            verify(stripeBillingService).createBillingPortalSession(USER_ID, customUrl);
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void portalWithoutUserHeader() throws Exception {
            mockMvc.perform(post("/api/billing/portal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("returnUrl", "https://myapp.com"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("should return 500 when Stripe throws exception")
        void portalStripeException() throws Exception {
            when(stripeBillingService.createBillingPortalSession(eq(USER_ID), anyString()))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            mockMvc.perform(post("/api/billing/portal")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("returnUrl", "https://myapp.com"))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("Erreur interne du serveur"));
        }

        @Test
        @DisplayName("should return 400 when user not found in billing service")
        void portalUserNotFound() throws Exception {
            when(stripeBillingService.createBillingPortalSession(eq(USER_ID), anyString()))
                    .thenThrow(new IllegalArgumentException("User not found"));

            mockMvc.perform(post("/api/billing/portal")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("returnUrl", "https://myapp.com"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("User not found"));
        }
    }

    // ======================== GET /api/billing/me ========================

    @Nested
    @DisplayName("GET /api/billing/me")
    class GetCurrentBillingStatus {

        @Test
        @DisplayName("should return subscription for user with active subscription")
        void meWithActiveSubscription() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Price proPrice = buildPrice(4L, proPlan, "monthly", 4900);
            Subscription sub = buildSubscriptionWithPrice(1L, proPlan, proPrice, bc, "active");

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.hasActiveSubscription").value(true))
                    .andExpect(jsonPath("$.subscription.status").value("active"))
                    .andExpect(jsonPath("$.subscription.planCode").value("PRO"))
                    .andExpect(jsonPath("$.subscription.planName").value("Pro"))
                    .andExpect(jsonPath("$.subscription.cadence").value("monthly"))
                    .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false))
                    .andExpect(jsonPath("$.subscription.provider").value("stripe"))
                    .andExpect(jsonPath("$.canUpgrade").value(true));
        }

        @Test
        @DisplayName("should return no subscription for user without subscription")
        void meWithNoSubscription() throws Exception {
            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.hasActiveSubscription").value(false))
                    .andExpect(jsonPath("$.canUpgrade").value(true))
                    .andExpect(jsonPath("$.status").value("no_subscription"))
                    .andExpect(jsonPath("$.plan").value("free"));
        }

        @Test
        @DisplayName("should return canUpgrade false for ENTERPRISE plan")
        void meWithEnterprisePlan() throws Exception {
            Plan entPlan = buildPlan(7L, "ENTERPRISE", "Enterprise");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, entPlan, bc, "active");

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canUpgrade").value(false));
        }

        @Test
        @DisplayName("should include no-cache headers in response")
        void meHasNoCacheHeaders() throws Exception {
            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(header().string("Cache-Control", containsString("no-cache")))
                    .andExpect(header().string("Pragma", "no-cache"))
                    .andExpect(header().string("Expires", "0"));
        }

        @Test
        @DisplayName("should return trialing subscription status")
        void meWithTrialingSubscription() throws Exception {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, starterPlan, bc, "trialing");

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveSubscription").value(true))
                    .andExpect(jsonPath("$.subscription.status").value("trialing"));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void meWithoutUserHeader() throws Exception {
            mockMvc.perform(get("/api/billing/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("should return 401 when X-User-ID is not numeric")
        void meWithInvalidUserId() throws Exception {
            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", "abc"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should use cadence from subscription when price is null")
        void meWithNoPriceUsesCadenceField() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "active");
            sub.setPrice(null);
            sub.setCadence("yearly");

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subscription.cadence").value("yearly"));
        }

        @Test
        @DisplayName("should handle subscription query exception gracefully")
        void meSubscriptionQueryException() throws Exception {
            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenThrow(new RuntimeException("DB connection failure"));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveSubscription").value(false))
                    .andExpect(jsonPath("$.canUpgrade").value(false))
                    .andExpect(jsonPath("$.status").value("error"));
        }
    }

    // ======================== GET /api/billing/plans ========================

    @Nested
    @DisplayName("GET /api/billing/plans")
    class GetPlans {

        @Test
        @DisplayName("should return all plans with prices")
        void getPlansWithPrices() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.FREE").exists())
                    .andExpect(jsonPath("$.STARTER").exists())
                    .andExpect(jsonPath("$.PRO").exists())
                    .andExpect(jsonPath("$.ENTERPRISE_BASIC").exists());
        }

        @Test
        @DisplayName("should include monthly and yearly prices for plans")
        void plansIncludeBothCadences() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.STARTER.prices.monthly.amount_cents").value(1900))
                    .andExpect(jsonPath("$.STARTER.prices.yearly.amount_cents").value(19000))
                    .andExpect(jsonPath("$.PRO.prices.monthly.amount_cents").value(4900))
                    .andExpect(jsonPath("$.PRO.prices.yearly.amount_cents").value(49000));
        }

        @Test
        @DisplayName("should include plan details")
        void plansIncludeDetails() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.STARTER.id").value(2))
                    .andExpect(jsonPath("$.STARTER.code").value("STARTER"))
                    .andExpect(jsonPath("$.STARTER.name").value("Starter"))
                    .andExpect(jsonPath("$.STARTER.description").exists());
            // included_tool_credits / included_storage_bytes / payg_* keys were retired
            // alongside the cycle-counter quota system. The DB columns are kept for
            // CreditAttributionService grant logic but no longer leak through this API.
        }

        @Test
        @DisplayName("should include amount_dollars computed from amount_cents")
        void plansIncludeAmountDollars() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.STARTER.prices.monthly.amount_dollars").value(19.0))
                    .andExpect(jsonPath("$.PRO.prices.monthly.amount_dollars").value(49.0));
        }

        @Test
        @DisplayName("should return 500 when cache throws exception")
        void plansOnCacheException() throws Exception {
            when(planCacheService.getAllPlans()).thenThrow(new RuntimeException("Cache initialization failed"));

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("should include all tiers in the response")
        void plansIncludeAllTiers() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.FREE").exists())
                    .andExpect(jsonPath("$.STARTER").exists())
                    .andExpect(jsonPath("$.PRO").exists())
                    .andExpect(jsonPath("$.ENTERPRISE_BASIC").exists())
                    .andExpect(jsonPath("$.ENTERPRISE_STANDARD").exists())
                    .andExpect(jsonPath("$.ENTERPRISE_PREMIUM").exists())
                    .andExpect(jsonPath("$.ENTERPRISE_ULTIMATE").exists())
                    .andExpect(jsonPath("$.PAYG").exists());
        }
    }

    // ======================== POST /api/billing/change-plan ========================

    @Nested
    @DisplayName("POST /api/billing/change-plan")
    class ChangePlan {

        @Test
        @DisplayName("should handle upgrade FREE to STARTER as checkout required")
        void upgradeFreeToStarter() throws Exception {
            Plan freePlan = buildPlan(1L, "FREE", "Free");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, freePlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(buildPlan(2L, "STARTER", "Starter")));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("STARTER"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/upgrade");

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "STARTER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("CHECKOUT_REQUIRED"))
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/upgrade"));
        }

        @Test
        @DisplayName("should handle upgrade FREE to PRO as checkout required")
        void upgradeFreeToProCheckoutRequired() throws Exception {
            Plan freePlan = buildPlan(1L, "FREE", "Free");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, freePlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(buildPlan(3L, "PRO", "Pro")));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/pro_upgrade");

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("CHECKOUT_REQUIRED"));
        }

        @Test
        @DisplayName("should handle immediate upgrade STARTER to PRO (null checkout URL)")
        void upgradeStarterToProImmediate() throws Exception {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, starterPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenReturn(null); // null URL = immediate swap

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("IMMEDIATE_UPGRADE"));
        }

        @Test
        @DisplayName("should handle upgrade STARTER to ENTERPRISE")
        void upgradeStarterToEnterprise() throws Exception {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan entPlan = buildPlan(4L, "ENTERPRISE_BASIC", "Enterprise Basic");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, starterPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));
            when(planRepository.findByCode("ENTERPRISE_BASIC")).thenReturn(Optional.of(entPlan));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("ENTERPRISE_BASIC"), eq("monthly"), eq(0)))
                    .thenReturn(null);

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "ENTERPRISE_BASIC"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("IMMEDIATE_UPGRADE"));
        }

        @Test
        @DisplayName("should schedule downgrade PRO to STARTER")
        void downgradeProToStarter() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));

            LocalDateTime effectiveDate = LocalDateTime.now().plusDays(15);
            PlanChangeResult scheduledResult = PlanChangeResult.scheduledDowngrade(
                    "PRO", "STARTER", effectiveDate, "sub_sched_123");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER")).thenReturn(scheduledResult);

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "STARTER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("SCHEDULED_DOWNGRADE"))
                    .andExpect(jsonPath("$.scheduleId").value("sub_sched_123"))
                    .andExpect(jsonPath("$.effectiveDate").exists());
        }

        @Test
        @DisplayName("should schedule downgrade PRO to FREE")
        void downgradeProToFree() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Plan freePlan = buildPlan(1L, "FREE", "Free");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(planRepository.findByCode("FREE")).thenReturn(Optional.of(freePlan));

            PlanChangeResult scheduledResult = PlanChangeResult.scheduledDowngrade(
                    "PRO", "FREE", LocalDateTime.now().plusDays(15), "sub_sched_456");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "FREE")).thenReturn(scheduledResult);

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "FREE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("SCHEDULED_DOWNGRADE"));
        }

        @Test
        @DisplayName("should schedule downgrade ENTERPRISE to PRO")
        void downgradeEnterpriseToPro() throws Exception {
            Plan entPlan = buildPlan(4L, "ENTERPRISE_BASIC", "Enterprise Basic");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, entPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("ENTERPRISE_BASIC")).thenReturn(Optional.of(entPlan));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));

            PlanChangeResult scheduledResult = PlanChangeResult.scheduledDowngrade(
                    "ENTERPRISE_BASIC", "PRO", LocalDateTime.now().plusDays(15), "sub_sched_789");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "PRO")).thenReturn(scheduledResult);

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("SCHEDULED_DOWNGRADE"));
        }

        @Test
        @DisplayName("should return 400 when targetPlanCode is missing")
        void changePlanMissingTargetCode() throws Exception {
            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("targetPlanCode est requis"));
        }

        @Test
        @DisplayName("should return 400 when targetPlanCode is empty string")
        void changePlanEmptyTargetCode() throws Exception {
            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", ""))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("targetPlanCode est requis"));
        }

        @Test
        @DisplayName("should return checkout_required when no active subscription")
        void changePlanNoActiveSubscription() throws Exception {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "STARTER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.action").value("checkout_required"));
        }

        @Test
        @DisplayName("should return 500 when Stripe fails during plan change")
        void changePlanStripeFailure() throws Exception {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, starterPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenThrow(new RuntimeException("Stripe error"));

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.changeType").value("ERROR"));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void changePlanWithoutUserHeader() throws Exception {
            mockMvc.perform(post("/api/billing/change-plan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("User not authenticated"));
        }

        @Test
        @DisplayName("should handle billing cycle change via change-plan")
        void changePlanWithBillingCycleOnly() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));

            // same plan code but with billing cycle -> triggers cycle change
            PlanChangeResult cycleResult = PlanChangeResult.builder()
                    .changeType(PlanChangeResult.ChangeType.BILLING_CYCLE_CHANGE)
                    .success(true)
                    .currentPlanCode("PRO")
                    .targetPlanCode("PRO")
                    .effectiveDate(LocalDateTime.now().plusDays(15))
                    .scheduleId("sub_sched_cycle")
                    .message("Your billing will switch to yearly")
                    .build();
            when(stripeScheduleService.scheduleBillingCycleChange(USER_ID, "yearly")).thenReturn(cycleResult);

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO", "billingCycle", "yearly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("BILLING_CYCLE_CHANGE"));
        }
    }

    // ======================== POST /api/billing/downgrade ========================

    @Nested
    @DisplayName("POST /api/billing/downgrade")
    class ScheduleDowngrade {

        @Test
        @DisplayName("should schedule downgrade PRO to STARTER at period end")
        void downgradeProToStarter() throws Exception {
            LocalDateTime effectiveDate = LocalDateTime.now().plusDays(15);
            PlanChangeResult result = PlanChangeResult.scheduledDowngrade("PRO", "STARTER", effectiveDate, "sub_sched_d1");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER")).thenReturn(result);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "STARTER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("SCHEDULED_DOWNGRADE"))
                    .andExpect(jsonPath("$.scheduleId").value("sub_sched_d1"))
                    .andExpect(jsonPath("$.effectiveDate").exists());
        }

        @Test
        @DisplayName("should schedule downgrade PRO to FREE at period end")
        void downgradeProToFree() throws Exception {
            LocalDateTime effectiveDate = LocalDateTime.now().plusDays(15);
            PlanChangeResult result = PlanChangeResult.scheduledDowngrade("PRO", "FREE", effectiveDate, "sub_sched_d2");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "FREE")).thenReturn(result);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "FREE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("SCHEDULED_DOWNGRADE"));
        }

        @Test
        @DisplayName("should return 400 with invalid target plan")
        void downgradeInvalidTargetPlan() throws Exception {
            PlanChangeResult errorResult = PlanChangeResult.error("This is not a downgrade. Use upgrade for higher plans.");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "INVALID")).thenReturn(errorResult);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "INVALID"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.changeType").value("ERROR"));
        }

        @Test
        @DisplayName("should return error when target is actually an upgrade")
        void downgradeWhenTargetIsUpgrade() throws Exception {
            PlanChangeResult errorResult = PlanChangeResult.error("This is not a downgrade. Use upgrade for higher plans.");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "ENTERPRISE_BASIC")).thenReturn(errorResult);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "ENTERPRISE_BASIC"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("should return error when no active subscription for downgrade")
        void downgradeNoActiveSubscription() throws Exception {
            PlanChangeResult errorResult = PlanChangeResult.error("No active subscription for user " + USER_ID);
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "FREE")).thenReturn(errorResult);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "FREE"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("should return 400 when targetPlanCode is missing")
        void downgradeNoTargetPlanCode() throws Exception {
            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("targetPlanCode is required"));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void downgradeNoUserHeader() throws Exception {
            mockMvc.perform(post("/api/billing/downgrade")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "FREE"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("User not authenticated"));
        }

        @Test
        @DisplayName("should return 500 when exception occurs during downgrade")
        void downgradeInternalError() throws Exception {
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "STARTER"))
                    .thenThrow(new RuntimeException("Unexpected error"));

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "STARTER"))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Internal error")));
        }
    }

    // ======================== POST /api/billing/change-cycle ========================

    @Nested
    @DisplayName("POST /api/billing/change-cycle")
    class ChangeBillingCycle {

        @Test
        @DisplayName("should apply immediate cycle change from monthly to yearly")
        void changeCycleMonthlyToYearly() throws Exception {
            // monthly -> yearly is applied immediately via swapPlanAndUpdateLocal
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Subscription localSub = buildSubscription(1L, proPlan, buildBillingCustomer(1L, buildUser(USER_ID)), "active");
            localSub.setCreditQuantity(0);

            when(stripeBillingService.getActiveSubscription(USER_ID)).thenReturn(localSub);
            when(stripeBillingService.swapPlanAndUpdateLocal(eq(USER_ID), eq("PRO"), eq(0), eq("yearly")))
                    .thenReturn(new StripeBillingService.UpgradeResult("sub_test_1", "PRO", "PRO", 1L));

            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "yearly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("IMMEDIATE_CYCLE_CHANGE"));
        }

        @Test
        @DisplayName("should schedule yearly to monthly cycle change")
        void changeCycleYearlyToMonthly() throws Exception {
            PlanChangeResult result = PlanChangeResult.builder()
                    .changeType(PlanChangeResult.ChangeType.BILLING_CYCLE_CHANGE)
                    .success(true)
                    .currentPlanCode("PRO")
                    .targetPlanCode("PRO")
                    .effectiveDate(LocalDateTime.now().plusDays(30))
                    .scheduleId("sub_sched_cy2")
                    .message("Your billing will switch to monthly")
                    .build();
            when(stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly")).thenReturn(result);

            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("BILLING_CYCLE_CHANGE"));
        }

        @Test
        @DisplayName("should return NO_CHANGE when same cycle is requested")
        void changeCycleSameCycle() throws Exception {
            PlanChangeResult result = PlanChangeResult.noChange("PRO");
            when(stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly")).thenReturn(result);

            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.changeType").value("NO_CHANGE"));
        }

        @Test
        @DisplayName("should return 400 when billingCycle is missing")
        void changeCycleMissingParam() throws Exception {
            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("billingCycle must be 'monthly' or 'yearly'"));
        }

        @Test
        @DisplayName("should return 400 when billingCycle is invalid value")
        void changeCycleInvalidValue() throws Exception {
            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "weekly"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("billingCycle must be 'monthly' or 'yearly'"));
        }

        @Test
        @DisplayName("should return error when no active subscription")
        void changeCycleNoActiveSubscription() throws Exception {
            // yearly->monthly goes through stripeScheduleService which returns error
            PlanChangeResult result = PlanChangeResult.error("No active subscription");
            when(stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly")).thenReturn(result);

            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.changeType").value("ERROR"));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void changeCycleNoUserHeader() throws Exception {
            mockMvc.perform(post("/api/billing/change-cycle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "yearly"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 500 when exception occurs during cycle change")
        void changeCycleInternalError() throws Exception {
            // yearly->monthly goes through stripeScheduleService
            when(stripeScheduleService.scheduleBillingCycleChange(USER_ID, "monthly"))
                    .thenThrow(new RuntimeException("Unexpected error"));

            mockMvc.perform(post("/api/billing/change-cycle")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("billingCycle", "monthly"))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Internal error")));
        }
    }

    // ======================== GET /api/billing/scheduled-change ========================

    @Nested
    @DisplayName("GET /api/billing/scheduled-change")
    class GetScheduledChange {

        @Test
        @DisplayName("should return existing scheduled change with full details")
        void getExistingScheduledChange() throws Exception {
            ScheduledChangeInfo info = buildScheduledChangeInfo();
            when(stripeScheduleService.getScheduledChange(USER_ID)).thenReturn(Optional.of(info));

            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasScheduledChange").value(true))
                    .andExpect(jsonPath("$.scheduledChange.scheduleId").value("sub_sched_123"))
                    .andExpect(jsonPath("$.scheduledChange.currentPlanCode").value("PRO"))
                    .andExpect(jsonPath("$.scheduledChange.currentPlanName").value("Pro"))
                    .andExpect(jsonPath("$.scheduledChange.targetPlanCode").value("STARTER"))
                    .andExpect(jsonPath("$.scheduledChange.targetPlanName").value("Starter"))
                    .andExpect(jsonPath("$.scheduledChange.changeType").value("downgrade"))
                    .andExpect(jsonPath("$.scheduledChange.status").value("active"))
                    .andExpect(jsonPath("$.scheduledChange.cancellable").value(true))
                    .andExpect(jsonPath("$.scheduledChange.userMessage").exists())
                    .andExpect(jsonPath("$.scheduledChange.effectiveDate").exists());
        }

        @Test
        @DisplayName("should return hasScheduledChange=false when no scheduled change")
        void getNoScheduledChange() throws Exception {
            when(stripeScheduleService.getScheduledChange(USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasScheduledChange").value(false));
        }

        @Test
        @DisplayName("should return all fields populated for scheduled change")
        void getScheduledChangeAllFieldsPopulated() throws Exception {
            ScheduledChangeInfo info = ScheduledChangeInfo.builder()
                    .scheduleId("sub_sched_full")
                    .currentPlanCode("ENTERPRISE_BASIC")
                    .currentPlanName("Enterprise Basic")
                    .targetPlanCode("PRO")
                    .targetPlanName("Pro")
                    .effectiveDate(LocalDateTime.of(2026, 3, 1, 0, 0))
                    .changeType("downgrade")
                    .currentBillingCycle("yearly")
                    .targetBillingCycle("yearly")
                    .status("not_started")
                    .cancellable(true)
                    .userMessage("Your plan will switch to Pro on 2026-03-01")
                    .build();
            when(stripeScheduleService.getScheduledChange(USER_ID)).thenReturn(Optional.of(info));

            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasScheduledChange").value(true))
                    .andExpect(jsonPath("$.scheduledChange.scheduleId").value("sub_sched_full"))
                    .andExpect(jsonPath("$.scheduledChange.currentPlanCode").value("ENTERPRISE_BASIC"))
                    .andExpect(jsonPath("$.scheduledChange.targetPlanCode").value("PRO"))
                    .andExpect(jsonPath("$.scheduledChange.status").value("not_started"))
                    .andExpect(jsonPath("$.scheduledChange.cancellable").value(true));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void getScheduledChangeNoUserHeader() throws Exception {
            mockMvc.perform(get("/api/billing/scheduled-change"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 500 when exception occurs")
        void getScheduledChangeInternalError() throws Exception {
            when(stripeScheduleService.getScheduledChange(USER_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Internal error")));
        }
    }

    // ======================== DELETE /api/billing/scheduled-change ========================

    @Nested
    @DisplayName("DELETE /api/billing/scheduled-change")
    class CancelScheduledChange {

        @Test
        @DisplayName("should cancel existing scheduled change successfully")
        void cancelExistingScheduledChange() throws Exception {
            when(stripeScheduleService.cancelScheduledChange(USER_ID)).thenReturn(true);

            mockMvc.perform(delete("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Scheduled change cancelled successfully"));
        }

        @Test
        @DisplayName("should return 400 when no scheduled change to cancel")
        void cancelNoScheduledChange() throws Exception {
            when(stripeScheduleService.cancelScheduledChange(USER_ID)).thenReturn(false);

            mockMvc.perform(delete("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("No scheduled change to cancel"));
        }

        @Test
        @DisplayName("should return 500 when Stripe fails during cancel")
        void cancelScheduledChangeStripeFailure() throws Exception {
            when(stripeScheduleService.cancelScheduledChange(USER_ID))
                    .thenThrow(new RuntimeException("Stripe timeout"));

            mockMvc.perform(delete("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Internal error")));
        }

        @Test
        @DisplayName("should return 401 without X-User-ID header")
        void cancelScheduledChangeNoUserHeader() throws Exception {
            mockMvc.perform(delete("/api/billing/scheduled-change"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ======================== GET /api/billing/checkout/finalize ========================

    @Nested
    @DisplayName("GET /api/billing/checkout/finalize")
    class FinalizeCheckout {

        private com.stripe.service.CheckoutService checkoutService;
        private SessionService sessionService;

        @BeforeEach
        void setUpStripeClient() {
            checkoutService = mock(com.stripe.service.CheckoutService.class);
            sessionService = mock(SessionService.class);
            when(stripeClient.checkout()).thenReturn(checkoutService);
            when(checkoutService.sessions()).thenReturn(sessionService);
        }

        @Test
        @DisplayName("should return provisioned when subscription exists in DB")
        void finalizeWithProvisionedSubscription() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("subscription");
            when(session.getSubscription()).thenReturn("sub_abc123");
            when(sessionService.retrieve("sess_123")).thenReturn(session);
            when(subscriptionService.existsByProviderSubscriptionId("sub_abc123")).thenReturn(true);

            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            Subscription sub = new Subscription();
            sub.setId(1L);
            sub.setPlan(proPlan);
            sub.setStatus("active");
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));
            when(subscriptionService.findByProviderSubscriptionId("sub_abc123"))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("provisioned"))
                    .andExpect(jsonPath("$.subscriptionId").value("sub_abc123"))
                    .andExpect(jsonPath("$.planId").value(3))
                    .andExpect(jsonPath("$.status").value("active"));
        }

        @Test
        @DisplayName("should return processing when webhook not yet received")
        void finalizeWithProcessing() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("subscription");
            when(session.getSubscription()).thenReturn("sub_pending");
            when(session.getStatus()).thenReturn("complete");
            when(sessionService.retrieve("sess_pending")).thenReturn(session);
            when(subscriptionService.existsByProviderSubscriptionId("sub_pending")).thenReturn(false);

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_pending"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.state").value("processing"))
                    .andExpect(jsonPath("$.retry_after").value(2));
        }

        @Test
        @DisplayName("should return error for expired session")
        void finalizeWithExpiredSession() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("subscription");
            when(session.getSubscription()).thenReturn(null);
            when(session.getCustomer()).thenReturn(null);
            when(session.getStatus()).thenReturn("expired");
            when(sessionService.retrieve("sess_expired")).thenReturn(session);

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_expired"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.state").value("error"))
                    .andExpect(jsonPath("$.error").value("Session expiree"));
        }

        @Test
        @DisplayName("should return error for non-subscription session")
        void finalizeWithNonSubscriptionMode() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("payment");
            when(sessionService.retrieve("sess_payment")).thenReturn(session);

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_payment"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.state").value("error"))
                    .andExpect(jsonPath("$.error").value("Session non SUBSCRIPTION"));
        }

        @Test
        @DisplayName("should return 500 when Stripe throws exception")
        void finalizeStripeException() throws Exception {
            when(sessionService.retrieve("sess_error"))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_error"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.state").value("error"));
        }

        @Test
        @DisplayName("should return processing for open session status")
        void finalizeWithOpenSession() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("subscription");
            when(session.getSubscription()).thenReturn(null);
            when(session.getCustomer()).thenReturn(null);
            when(session.getStatus()).thenReturn("open");
            when(sessionService.retrieve("sess_open")).thenReturn(session);

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_open"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.state").value("processing"));
        }

        @Test
        @DisplayName("should return provisioned fallback when detail retrieval fails")
        void finalizeProvisionedFallbackOnDetailError() throws Exception {
            Session session = mock(Session.class);
            when(session.getMode()).thenReturn("subscription");
            when(session.getSubscription()).thenReturn("sub_detail_err");
            when(sessionService.retrieve("sess_detail_err")).thenReturn(session);
            when(subscriptionService.existsByProviderSubscriptionId("sub_detail_err")).thenReturn(true);
            when(subscriptionService.findByProviderSubscriptionId("sub_detail_err"))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/billing/checkout/finalize")
                            .param("session_id", "sess_detail_err"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("provisioned"))
                    .andExpect(jsonPath("$.subscriptionId").value("sub_detail_err"));
        }
    }

    // ======================== GET /api/billing/invoices ========================

    @Nested
    @DisplayName("GET /api/billing/invoices")
    class GetInvoices {

        private InvoiceService invoiceService;

        @BeforeEach
        void setUpInvoiceService() {
            // lenient: the no-BillingCustomer branch returns before touching Stripe.
            invoiceService = mock(InvoiceService.class);
            lenient().when(stripeClient.invoices()).thenReturn(invoiceService);
        }

        /** A fully populated paid Stripe invoice (epoch seconds are round UTC dates). */
        private Invoice buildStripeInvoice(String id) {
            Invoice inv = new Invoice();
            inv.setId(id);
            inv.setNumber("INV-" + id);
            inv.setAmountPaid(4900L);
            inv.setAmountDue(0L);
            inv.setCurrency("usd");
            inv.setStatus("paid");
            inv.setCreated(1735689600L);     // 2025-01-01T00:00:00Z
            inv.setPeriodStart(1733011200L); // 2024-12-01T00:00:00Z
            inv.setPeriodEnd(1735689600L);   // 2025-01-01T00:00:00Z
            inv.setHostedInvoiceUrl("https://invoice.stripe.com/i/" + id);
            inv.setInvoicePdf("https://pay.stripe.com/invoice/" + id + "/pdf");
            return inv;
        }

        /**
         * Build the mocked collection BEFORE any outer {@code when(...)} call -
         * this helper stubs {@code getData()}, and nesting it inside a
         * {@code thenReturn(...)} would interleave two stubbings
         * (UnfinishedStubbingException).
         */
        private StripeCollection<Invoice> collectionOf(List<Invoice> data) {
            @SuppressWarnings("unchecked")
            StripeCollection<Invoice> collection = mock(StripeCollection.class);
            when(collection.getData()).thenReturn(data);
            return collection;
        }

        private void givenStripeReturnsInvoices(List<Invoice> data) throws com.stripe.exception.StripeException {
            StripeCollection<Invoice> collection = collectionOf(data);
            when(invoiceService.list(any(InvoiceListParams.class))).thenReturn(collection);
        }

        private void givenBillingCustomerExists() {
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user); // providerCustomerId = cus_test_1
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));
        }

        @Test
        @DisplayName("returns empty list with 200 and never calls Stripe when the user has no BillingCustomer (regression: GET /invoices had zero tests)")
        void invoicesWithoutBillingCustomer_returnsEmptyList() throws Exception {
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.invoices").isArray())
                    .andExpect(jsonPath("$.invoices", hasSize(0)));

            verify(stripeClient, never()).invoices();
        }

        @Test
        @DisplayName("returns empty list when a BillingCustomer row exists but has no providerCustomerId (never-checked-out user)")
        void invoicesWithBillingCustomerButNoProviderId_returnsEmptyList() throws Exception {
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            bc.setProviderCustomerId(null);
            when(billingCustomerRepository.findByUserId(USER_ID)).thenReturn(Optional.of(bc));

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoices", hasSize(0)));

            verify(stripeClient, never()).invoices();
        }

        @Test
        @DisplayName("maps every Stripe invoice field to the DTO (number, status, amounts, UTC dates, hosted URL, PDF) preserving Stripe order")
        void invoicesHappyPath_mapsFullDto() throws Exception {
            givenBillingCustomerExists();

            Invoice paid = buildStripeInvoice("in_001");
            Invoice open = buildStripeInvoice("in_002");
            open.setStatus("open");
            open.setAmountPaid(0L);
            open.setAmountDue(4900L);

            givenStripeReturnsInvoices(List.of(paid, open));

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoices", hasSize(2)))
                    // newest-first Stripe order is preserved as-is
                    .andExpect(jsonPath("$.invoices[0].id").value("in_001"))
                    .andExpect(jsonPath("$.invoices[0].number").value("INV-in_001"))
                    .andExpect(jsonPath("$.invoices[0].status").value("paid"))
                    .andExpect(jsonPath("$.invoices[0].amountPaid").value(4900))
                    .andExpect(jsonPath("$.invoices[0].amountDue").value(0))
                    .andExpect(jsonPath("$.invoices[0].currency").value("usd"))
                    // Instant fields: the standalone-MockMvc ObjectMapper (no Boot
                    // auto-config) writes Instant as epoch seconds; assert the epoch
                    // value so the test pins the DTO carrying the RIGHT instant
                    // (1735689600 = 2025-01-01T00:00:00Z) without depending on the
                    // harness's date rendering. Prod Boot Jackson renders ISO-8601.
                    .andExpect(jsonPath("$.invoices[0].created",
                            org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.startsWith("1735689600"))))
                    .andExpect(jsonPath("$.invoices[0].periodStart",
                            org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.startsWith("1733011200"))))
                    .andExpect(jsonPath("$.invoices[0].periodEnd",
                            org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.startsWith("1735689600"))))
                    .andExpect(jsonPath("$.invoices[0].hostedInvoiceUrl").value("https://invoice.stripe.com/i/in_001"))
                    .andExpect(jsonPath("$.invoices[0].invoicePdf").value("https://pay.stripe.com/invoice/in_001/pdf"))
                    .andExpect(jsonPath("$.invoices[1].id").value("in_002"))
                    .andExpect(jsonPath("$.invoices[1].status").value("open"))
                    .andExpect(jsonPath("$.invoices[1].amountPaid").value(0))
                    .andExpect(jsonPath("$.invoices[1].amountDue").value(4900));
        }

        @Test
        @DisplayName("requests Stripe with the customer's providerCustomerId and the 12-invoice cap")
        void invoicesListParams_scopedToCustomerAndCapped() throws Exception {
            givenBillingCustomerExists();
            givenStripeReturnsInvoices(List.of(buildStripeInvoice("in_cap")));

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk());

            ArgumentCaptor<InvoiceListParams> captor = ArgumentCaptor.forClass(InvoiceListParams.class);
            verify(invoiceService).list(captor.capture());
            assertThat(captor.getValue().getCustomer()).isEqualTo("cus_test_1");
            assertThat(captor.getValue().getLimit()).isEqualTo(12L);
        }

        @Test
        @DisplayName("sets Cache-Control: no-store on the listing (hostedInvoiceUrl/invoicePdf are expiring signed URLs)")
        void invoicesResponse_hasNoStoreHeader() throws Exception {
            givenBillingCustomerExists();
            givenStripeReturnsInvoices(List.of(buildStripeInvoice("in_hdr")));

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        @DisplayName("returns empty list when the Stripe collection carries null data")
        void invoicesNullStripeData_returnsEmptyList() throws Exception {
            givenBillingCustomerExists();
            givenStripeReturnsInvoices(null);

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoices", hasSize(0)));
        }

        @Test
        @DisplayName("serializes null timestamps and null URLs as JSON null for draft invoices (toInvoiceDto null-safety)")
        void invoicesDraftWithNullFields_serializedAsNull() throws Exception {
            givenBillingCustomerExists();

            Invoice draft = new Invoice();
            draft.setId("in_draft");
            draft.setStatus("draft");
            draft.setAmountDue(1900L);
            draft.setAmountPaid(0L);
            draft.setCurrency("usd");
            // number, created, periodStart, periodEnd, hostedInvoiceUrl, invoicePdf all left null

            givenStripeReturnsInvoices(List.of(draft));

            mockMvc.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoices[0].id").value("in_draft"))
                    .andExpect(jsonPath("$.invoices[0].number").value(nullValue()))
                    .andExpect(jsonPath("$.invoices[0].created").value(nullValue()))
                    .andExpect(jsonPath("$.invoices[0].periodStart").value(nullValue()))
                    .andExpect(jsonPath("$.invoices[0].periodEnd").value(nullValue()))
                    .andExpect(jsonPath("$.invoices[0].hostedInvoiceUrl").value(nullValue()))
                    .andExpect(jsonPath("$.invoices[0].invoicePdf").value(nullValue()));
        }

        @Test
        @DisplayName("returns 401 without X-User-ID header")
        void invoicesWithoutUserHeader_returns401() throws Exception {
            mockMvc.perform(get("/api/billing/invoices"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("User not authenticated"));
        }

        @Test
        @DisplayName("propagates StripeException to @ControllerAdvice: resource_missing maps to 404 not_found (controller has no catch)")
        void invoicesStripeResourceMissing_propagatesAs404() throws Exception {
            MockMvc mvcWithAdvice = MockMvcBuilders.standaloneSetup(billingController)
                    .setControllerAdvice(new StripeExceptionHandler())
                    .defaultRequest(post("/").header("X-Organization-Role", "OWNER"))
                    .build();

            givenBillingCustomerExists();
            InvalidRequestException stripeError = new InvalidRequestException(
                    "No such customer: 'cus_test_1'", null, "req_inv_404",
                    "resource_missing", 404, null);
            when(invoiceService.list(any(InvoiceListParams.class))).thenThrow(stripeError);

            mvcWithAdvice.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }

        @Test
        @DisplayName("propagates generic Stripe ApiException to @ControllerAdvice as 500 payment_error")
        void invoicesStripeApiException_propagatesAs500() throws Exception {
            MockMvc mvcWithAdvice = MockMvcBuilders.standaloneSetup(billingController)
                    .setControllerAdvice(new StripeExceptionHandler())
                    .defaultRequest(post("/").header("X-Organization-Role", "OWNER"))
                    .build();

            givenBillingCustomerExists();
            when(invoiceService.list(any(InvoiceListParams.class)))
                    .thenThrow(new ApiException("Stripe internal error", "req_inv_500", null, 500, null));

            mvcWithAdvice.perform(get("/api/billing/invoices")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("payment_error"));
        }
    }

    // ======================== ADDITIONAL EDGE CASE TESTS ========================

    @Nested
    @DisplayName("Edge cases and additional scenarios")
    class EdgeCases {

        @Test
        @DisplayName("should handle checkout for ENTERPRISE_STANDARD plan")
        void checkoutEnterpriseStandard() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("ENTERPRISE_STANDARD"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_ent_std");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_ent_std_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "ENTERPRISE_STANDARD", "billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_ent_std"));
        }

        @Test
        @DisplayName("should handle checkout for ENTERPRISE_PREMIUM plan yearly")
        void checkoutEnterprisePremiumYearly() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("ENTERPRISE_PREMIUM"), eq("yearly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_ent_prem_y");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_ent_prem_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "ENTERPRISE_PREMIUM", "billingCycle", "yearly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_ent_prem_y"));
        }

        @Test
        @DisplayName("should handle checkout for ENTERPRISE_ULTIMATE plan")
        void checkoutEnterpriseUltimate() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("ENTERPRISE_ULTIMATE"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_ent_ult");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_ent_ult_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "ENTERPRISE_ULTIMATE", "billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_ent_ult"));
        }

        @Test
        @DisplayName("should handle portal when returnUrl is null")
        void portalSessionNullReturnUrl() throws Exception {
            when(stripeBillingService.createBillingPortalSession(eq(USER_ID), isNull()))
                    .thenReturn("https://billing.stripe.com/portal_null_return");

            // Send a request body that has no returnUrl key
            mockMvc.perform(post("/api/billing/portal")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://billing.stripe.com/portal_null_return"));
        }

        @Test
        @DisplayName("should handle me endpoint for past_due subscription")
        void meWithPastDueSubscription() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "past_due");

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveSubscription").value(true))
                    .andExpect(jsonPath("$.subscription.status").value("past_due"));
        }

        @Test
        @DisplayName("should handle me endpoint for subscription with cancelAtPeriodEnd=true")
        void meWithCancelAtPeriodEnd() throws Exception {
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, proPlan, bc, "active");
            sub.setCancelAtPeriodEnd(true);

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true));
        }

        @Test
        @DisplayName("should handle scheduled change with billing_cycle_change type")
        void getScheduledChangeBillingCycleType() throws Exception {
            ScheduledChangeInfo info = ScheduledChangeInfo.builder()
                    .scheduleId("sub_sched_bc")
                    .currentPlanCode("PRO")
                    .currentPlanName("Pro")
                    .targetPlanCode("PRO")
                    .targetPlanName("Pro")
                    .effectiveDate(LocalDateTime.now().plusDays(15))
                    .changeType("billing_cycle_change")
                    .currentBillingCycle("monthly")
                    .targetBillingCycle("yearly")
                    .status("active")
                    .cancellable(true)
                    .userMessage("Your billing cycle will change from monthly to yearly")
                    .build();
            when(stripeScheduleService.getScheduledChange(USER_ID)).thenReturn(Optional.of(info));

            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasScheduledChange").value(true))
                    .andExpect(jsonPath("$.scheduledChange.changeType").value("billing_cycle_change"))
                    .andExpect(jsonPath("$.scheduledChange.currentPlanCode").value("PRO"))
                    .andExpect(jsonPath("$.scheduledChange.targetPlanCode").value("PRO"));
        }

        @Test
        @DisplayName("should handle different user IDs for isolation")
        void differentUserIdsIsolated() throws Exception {
            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(2L), any()))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(2))
                    .andExpect(jsonPath("$.hasActiveSubscription").value(false));
        }

        @Test
        @DisplayName("should handle checkout for PAYG plan")
        void checkoutPaygPlan() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PAYG"), eq("monthly"), eq(0)))
                    .thenReturn("https://checkout.stripe.com/session_payg");
            when(nonceUtil.generateNonce(USER_ID)).thenReturn("n_payg_nonce");
            when(billingEventRepository.save(any())).thenReturn(null);

            mockMvc.perform(post("/api/billing/checkout")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("planCode", "PAYG", "billingCycle", "monthly"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/session_payg"));
        }

        @Test
        @DisplayName("should handle me endpoint when plan is null (fallback to FREE)")
        void meWhenPlanIsNull() throws Exception {
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = new Subscription();
            sub.setId(1L);
            sub.setPlan(null);
            sub.setBillingCustomer(bc);
            sub.setStatus("active");
            sub.setProvider("stripe");
            sub.setProviderSubscriptionId("sub_no_plan");
            sub.setCadence("monthly");
            sub.setQuantity(1);
            sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
            sub.setCancelAtPeriodEnd(false);

            when(subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
                    eq(USER_ID), any()))
                    .thenReturn(Optional.of(sub));

            mockMvc.perform(get("/api/billing/me")
                            .header("X-User-ID", USER_ID_STR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveSubscription").value(true))
                    .andExpect(jsonPath("$.subscription.planCode").value("FREE"))
                    .andExpect(jsonPath("$.canUpgrade").value(true));
        }

        @Test
        @DisplayName("should handle change plan when upgrade throws and result is error")
        void changePlanUpgradeError() throws Exception {
            Plan starterPlan = buildPlan(2L, "STARTER", "Starter");
            Plan proPlan = buildPlan(3L, "PRO", "Pro");
            User user = buildUser(USER_ID);
            BillingCustomer bc = buildBillingCustomer(1L, user);
            Subscription sub = buildSubscription(1L, starterPlan, bc, "active");

            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starterPlan));
            when(planRepository.findByCode("PRO")).thenReturn(Optional.of(proPlan));
            when(stripeBillingService.createCheckoutSession(eq(USER_ID), eq("PRO"), eq("monthly"), eq(0)))
                    .thenThrow(new IllegalArgumentException("Currency mismatch"));

            mockMvc.perform(post("/api/billing/change-plan")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "PRO"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.changeType").value("ERROR"))
                    .andExpect(jsonPath("$.message").value("Currency mismatch"));
        }

        @Test
        @DisplayName("should handle downgrade when Stripe error returns error result")
        void downgradeStripeErrorResult() throws Exception {
            PlanChangeResult errorResult = PlanChangeResult.error("Stripe error: rate limited");
            when(stripeScheduleService.scheduleDowngrade(USER_ID, "FREE")).thenReturn(errorResult);

            mockMvc.perform(post("/api/billing/downgrade")
                            .header("X-User-ID", USER_ID_STR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("targetPlanCode", "FREE"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message", containsString("Stripe error")));
        }

        @Test
        @DisplayName("should include currency in plan prices")
        void planPricesIncludeCurrency() throws Exception {
            Map<String, Plan> plans = buildAllPlansMap();
            Map<String, Price> prices = buildAllPricesMap();
            when(planCacheService.getAllPlans()).thenReturn(plans);
            when(planCacheService.getAllPrices()).thenReturn(prices);

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.STARTER.prices.monthly.currency").value("usd"));
        }
    }
}
