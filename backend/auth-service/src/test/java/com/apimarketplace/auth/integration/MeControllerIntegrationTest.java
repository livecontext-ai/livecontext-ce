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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for MeController with full Spring context.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@DisplayName("MeController Integration Tests")
class MeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private BillingCustomerRepository billingCustomerRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private User testUser;
    private static final String USER_ID_HEADER = "X-User-ID";

    @BeforeEach
    void setUp() {
        testUser = new User("meuser", "me@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setFirstName("Me");
        testUser.setLastName("Controller");
        testUser.setEnabled(true);
        testUser.setRoles(Set.of("USER"));
        testUser.setUserVersion(1L);
        testUser = userRepository.saveAndFlush(testUser);
    }

    @Nested
    @DisplayName("GET /api/me")
    class GetProfile {

        @Test
        @DisplayName("Should return basic profile when no active subscription")
        void shouldReturnBasicProfileWithoutSubscription() throws Exception {
            mockMvc.perform(get("/api/me")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(testUser.getId()))
                    .andExpect(jsonPath("$.planCode").value("FREE"))
                    .andExpect(jsonPath("$.subscriptionStatus").value("no_subscription"));
        }

        @Test
        @DisplayName("Should return full profile with active subscription")
        void shouldReturnFullProfileWithSubscription() throws Exception {
            // Create plan, billing customer, and subscription
            Plan freePlan = new Plan("FREE", "Free Plan", "Basic free plan");
            freePlan.setIncludedToolCredits(100L);
            freePlan = planRepository.saveAndFlush(freePlan);

            BillingCustomer customer = new BillingCustomer(testUser, "internal");
            customer = billingCustomerRepository.saveAndFlush(customer);

            Subscription sub = new Subscription();
            sub.setBillingCustomer(customer);
            sub.setPlan(freePlan);
            sub.setStatus("active");
            sub.setCadence("monthly");
            sub.setProvider("internal");
            sub.setQuantity(1);
            sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
            sub.setCancelAtPeriodEnd(false);
            subscriptionRepository.saveAndFlush(sub);

            mockMvc.perform(get("/api/me")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(testUser.getId()))
                    .andExpect(jsonPath("$.username").value("meuser"))
                    .andExpect(jsonPath("$.email").value("me@example.com"))
                    .andExpect(jsonPath("$.planCode").value("FREE"))
                    .andExpect(jsonPath("$.planName").value("Free Plan"))
                    .andExpect(jsonPath("$.subscriptionStatus").value("active"))
                    .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        }

        @Test
        @DisplayName("Should return 401 when X-User-ID header is missing")
        void shouldReturn401WhenUserIdHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 401 for invalid X-User-ID format")
        void shouldReturn401ForInvalidUserIdFormat() throws Exception {
            mockMvc.perform(get("/api/me")
                            .header(USER_ID_HEADER, "invalid-id"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/me/subscription")
    class GetSubscription {

        @Test
        @DisplayName("Should return 404 when no active subscription")
        void shouldReturn404WhenNoSubscription() throws Exception {
            mockMvc.perform(get("/api/me/subscription")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return subscription details when active")
        void shouldReturnSubscriptionDetails() throws Exception {
            Plan freePlan = new Plan("FREE", "Free Plan", "Basic");
            freePlan = planRepository.saveAndFlush(freePlan);

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

            mockMvc.perform(get("/api/me/subscription")
                            .header(USER_ID_HEADER, testUser.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.planCode").value("FREE"))
                    .andExpect(jsonPath("$.planName").value("Free Plan"))
                    .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        }

        @Test
        @DisplayName("Should return 401 when header is missing")
        void shouldReturn401WhenHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/me/subscription"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // /api/me/quotas was retired alongside the cycle-counter quota system. Wallet
    // balance lives under /api/credits/balance (CreditService).
}
