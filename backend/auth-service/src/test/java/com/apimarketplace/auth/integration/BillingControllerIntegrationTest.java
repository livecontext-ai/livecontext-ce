package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for BillingController.
 * Uses @MockBean for Stripe-related external services while testing
 * the full request lifecycle with real database operations.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("BillingController Integration Tests")
class BillingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceRepository priceRepository;

    @Autowired
    private BillingCustomerRepository billingCustomerRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @MockitoBean
    private StripeBillingService stripeBillingService;

    @MockitoBean
    private PlanCacheService planCacheService;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Plan freePlan;
    private Plan starterPlan;
    private static final String USER_ID_HEADER = "X-User-ID";
    /**
     * Milestone-1 audit fix: all billing write endpoints require OWNER role
     * of the active workspace (PR5 P2 guard). Integration tests previously
     * only sent X-User-ID; without X-Organization-Role=OWNER, the guard
     * fails closed with 403 NOT_WORKSPACE_OWNER. Tests below set this header
     * on every write - read endpoints (GET /plans, /me, /scheduled-change)
     * skip the guard and don't need it.
     */
    private static final String ORG_ROLE_HEADER = "X-Organization-Role";

    @BeforeEach
    void setUp() {
        testUser = new User("billctrluser", "billctrl@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setEnabled(true);
        testUser.setRoles(Set.of("USER"));
        testUser.setUserVersion(1L);
        testUser = userRepository.saveAndFlush(testUser);

        freePlan = new Plan("FREE", "Free Plan", "Basic free plan");
        freePlan.setIncludedToolCredits(100L);
        freePlan = planRepository.saveAndFlush(freePlan);

        starterPlan = new Plan("STARTER", "Starter Plan", "Starter paid plan");
        starterPlan.setIncludedToolCredits(1000L);
        starterPlan = planRepository.saveAndFlush(starterPlan);

        Price starterMonthly = new Price(starterPlan, "monthly", 2900);
        starterMonthly.setProviderPriceId("price_starter_monthly");
        priceRepository.saveAndFlush(starterMonthly);
    }

    @Nested
    @DisplayName("GET /api/billing/plans")
    class GetPlans {

        @Test
        @DisplayName("Should return plans from cache - public endpoint")
        void shouldReturnPlansFromCache() throws Exception {
            // Configure the mock cache to return our plans
            when(planCacheService.getAllPlans()).thenReturn(Map.of(
                    "FREE", freePlan,
                    "STARTER", starterPlan
            ));
            when(planCacheService.getAllPrices()).thenReturn(Map.of());

            mockMvc.perform(get("/api/billing/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.FREE").exists())
                    .andExpect(jsonPath("$.FREE.code").value("FREE"))
                    .andExpect(jsonPath("$.FREE.name").value("Free Plan"))
                    .andExpect(jsonPath("$.STARTER").exists())
                    .andExpect(jsonPath("$.STARTER.code").value("STARTER"));
        }
    }

    @Nested
    @DisplayName("GET /api/billing/me")
    class GetBillingStatus {

        @Test
        @DisplayName("Should return no subscription for user without one")
        void shouldReturnNoSubscription() throws Exception {
            mockMvc.perform(get("/api/billing/me")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(testUser.getId()))
                    .andExpect(jsonPath("$.hasActiveSubscription").value(false));
        }

        @Test
        @DisplayName("Should return active subscription details")
        void shouldReturnActiveSubscription() throws Exception {
            BillingCustomer customer = new BillingCustomer(testUser, "stripe");
            customer.setProviderCustomerId("cus_test_bill");
            customer = billingCustomerRepository.saveAndFlush(customer);

            Subscription sub = new Subscription();
            sub.setBillingCustomer(customer);
            sub.setPlan(starterPlan);
            sub.setStatus("active");
            sub.setCadence("monthly");
            sub.setProvider("stripe");
            sub.setProviderSubscriptionId("sub_test_bill");
            sub.setQuantity(1);
            sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(5));
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(25));
            sub.setCancelAtPeriodEnd(false);
            subscriptionRepository.saveAndFlush(sub);

            mockMvc.perform(get("/api/billing/me")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasActiveSubscription").value(true))
                    .andExpect(jsonPath("$.subscription.status").value("active"))
                    .andExpect(jsonPath("$.subscription.planCode").value("STARTER"))
                    .andExpect(jsonPath("$.subscription.planName").value("Starter Plan"))
                    .andExpect(jsonPath("$.subscription.providerSubscriptionId").value("sub_test_bill"))
                    .andExpect(jsonPath("$.canUpgrade").value(true));
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/billing/me"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/billing/checkout")
    class CreateCheckout {

        @Test
        @DisplayName("Should return error for invalid plan code")
        void shouldReturnErrorForInvalidPlanCode() throws Exception {
            when(planCacheService.getAllPlans()).thenReturn(Map.of("FREE", freePlan, "STARTER", starterPlan));

            Map<String, String> request = Map.of(
                    "planCode", "INVALID_PLAN",
                    "billingCycle", "monthly"
            );

            mockMvc.perform(post("/api/billing/checkout")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(ORG_ROLE_HEADER, "OWNER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return error when plan code is missing")
        void shouldReturnErrorWhenPlanCodeMissing() throws Exception {
            Map<String, String> request = Map.of("billingCycle", "monthly");

            mockMvc.perform(post("/api/billing/checkout")
                            .header(USER_ID_HEADER, testUser.getId().toString())
                            .header(ORG_ROLE_HEADER, "OWNER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            Map<String, String> request = Map.of("planCode", "STARTER", "billingCycle", "monthly");

            mockMvc.perform(post("/api/billing/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/billing/config")
    class GetConfig {

        @Test
        @DisplayName("Should return Stripe configuration info")
        void shouldReturnStripeConfig() throws Exception {
            mockMvc.perform(get("/api/billing/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stripeConfigured").value(true))
                    .andExpect(jsonPath("$.webhookEndpoint").value("/api/webhooks/stripe"))
                    .andExpect(jsonPath("$.checkoutEndpoint").value("/api/billing/checkout"))
                    .andExpect(jsonPath("$.portalEndpoint").value("/api/billing/portal"));
        }
    }

    @Nested
    @DisplayName("POST /api/billing/portal")
    class CreatePortal {

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            Map<String, String> request = Map.of("returnUrl", "http://localhost:3000/billing");

            mockMvc.perform(post("/api/billing/portal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/billing/scheduled-change")
    class GetScheduledChange {

        @Test
        @DisplayName("Should return no scheduled change")
        void shouldReturnNoScheduledChange() throws Exception {
            when(planCacheService.getAllPlans()).thenReturn(Map.of("FREE", freePlan));

            // StripeScheduleService is mocked, returns empty by default
            mockMvc.perform(get("/api/billing/scheduled-change")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasScheduledChange").value(false));
        }
    }
}
