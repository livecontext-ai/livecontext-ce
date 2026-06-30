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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for SubscriptionController.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("SubscriptionController Integration Tests")
class SubscriptionControllerIntegrationTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Plan freePlan;
    private Plan starterPlan;
    private static final String USER_ID_HEADER = "X-User-ID";

    @BeforeEach
    void setUp() {
        testUser = new User("subuser", "sub@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
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
    @DisplayName("GET /api/subscriptions/current")
    class GetCurrentSubscription {

        @Test
        @DisplayName("Should return 404 when no active subscription")
        void shouldReturn404WhenNoActiveSubscription() throws Exception {
            mockMvc.perform(get("/api/subscriptions/current")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return current subscription details")
        void shouldReturnCurrentSubscription() throws Exception {
            // Set up subscription chain
            BillingCustomer customer = new BillingCustomer(testUser, "internal");
            customer = billingCustomerRepository.saveAndFlush(customer);

            Subscription sub = new Subscription();
            sub.setBillingCustomer(customer);
            sub.setPlan(freePlan);
            sub.setStatus("active");
            sub.setCadence("monthly");
            sub.setProvider("internal");
            sub.setQuantity(1);
            sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(10));
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(20));
            sub.setCancelAtPeriodEnd(false);
            subscriptionRepository.saveAndFlush(sub);

            mockMvc.perform(get("/api/subscriptions/current")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.planId").value("FREE"))
                    .andExpect(jsonPath("$.planName").value("Free Plan"))
                    .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        }

        @Test
        @DisplayName("Should return 401 when user ID header is missing")
        void shouldReturn401WhenHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/subscriptions/current"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // The /api/subscriptions/quotas/{current,status,consume} endpoints were retired
    // alongside the cycle-counter quota system. Credit-wallet metrics live under
    // /api/credits/* (CreditService) and have their own integration coverage.
}
