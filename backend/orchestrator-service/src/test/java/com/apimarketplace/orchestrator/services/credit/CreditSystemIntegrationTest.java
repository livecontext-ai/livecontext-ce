package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.scaling.cache.DistributedBudgetCache;
import com.apimarketplace.common.scaling.cache.InMemoryBudgetCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests verifying the credit system hardening fixes.
 * Tests the interaction between CreditConsumptionClient and CreditBudgetService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Credit System Integration Tests")
class CreditSystemIntegrationTest {

    @Mock
    private RestTemplate restTemplate;

    private CreditConsumptionClient creditClient;
    private DistributedBudgetCache budgetCache;
    private CreditBudgetService budgetService;

    private static final String USER_ID = "user-exploit-test";
    private static final String AUTH_URL = "http://localhost:8083";

    @BeforeEach
    void setUp() {
        creditClient = new CreditConsumptionClient(AUTH_URL, true);
        ReflectionTestUtils.setField(creditClient, "restTemplate", restTemplate);
        budgetCache = new InMemoryBudgetCache();
        budgetService = new CreditBudgetService(creditClient, budgetCache);
    }

    @Nested
    @DisplayName("Bug 1: 0.48-credit bypass")
    class SubOneCreditBypass {

        @Test
        @DisplayName("balance=0.48 should be blocked by fetchBalance returning 0 on fail-closed")
        void balanceBelowOneShouldBeBlocked() {
            // When auth-service returns balance of 0.48
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("balance", 0.48)));

            budgetService.initBudget(USER_ID);

            // Local budget should reflect 0.48 - less than 1 credit
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("0.48"));

            // tryConsumeOne (cost=1) should fail since 0.48 < 1
            assertThat(budgetService.tryConsumeOne(USER_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("Bug 3: Fail-closed on auth-service down")
    class FailClosedTests {

        @Test
        @DisplayName("checkCredits should return false when auth-service is unreachable and no cache")
        void checkCreditsShouldFailClosed() {
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/check"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            boolean result = creditClient.checkCredits(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("checkCredits should use cached result when auth-service fails")
        void checkCreditsShouldUseCacheOnFailure() {
            // First call succeeds
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/check"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true)));

            assertThat(creditClient.checkCredits(USER_ID)).isTrue();

            // Second call fails - should use cache
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/check"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            assertThat(creditClient.checkCredits(USER_ID)).isTrue(); // cached result
        }

        @Test
        @DisplayName("fetchBalance should return ZERO when auth-service is unreachable")
        void fetchBalanceShouldReturnZeroOnFailure() {
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            BigDecimal balance = creditClient.fetchBalance(USER_ID);

            assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Bug 4,6: Concurrent budget sharing")
    class ConcurrentBudgetSharing {

        @Test
        @DisplayName("two workflows should share the same budget via computeIfAbsent")
        void twoWorkflowsShouldShareBudget() {
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("balance", 10)));

            // Workflow 1 starts
            budgetService.initBudget(USER_ID);
            budgetService.incrementActiveWorkflows(USER_ID);

            // Consume 3 credits
            for (int i = 0; i < 3; i++) {
                budgetService.tryConsumeOne(USER_ID);
            }

            // Workflow 2 starts - should NOT reset budget
            budgetService.initBudget(USER_ID);
            budgetService.incrementActiveWorkflows(USER_ID);

            // Budget should still be 7, not 10
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("7"));

            // fetchBalance called only once
            verify(restTemplate, times(1)).exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class));
        }
    }

    @Nested
    @DisplayName("Bug 5: Agent iteration exhaustion")
    class AgentIterationExhaustion {

        @Test
        @DisplayName("guard should stop agent at iteration 3 when budget=3")
        void guardShouldStopAtBudgetLimit() {
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("balance", 3)));

            budgetService.initBudget(USER_ID);

            // Simulate pre-iteration guard
            int iterations = 0;
            for (int i = 0; i < 5; i++) {
                if (budgetService.tryConsumeOne(USER_ID)) {
                    iterations++;
                } else {
                    break;
                }
            }

            assertThat(iterations).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Bug 6: Burst trigger protection")
    class BurstTriggerProtection {

        @Test
        @DisplayName("10 rapid triggers with balance=5 - at most 5 should pass budget check")
        void burstTriggersShouldBeLimited() {
            when(restTemplate.exchange(
                    eq(AUTH_URL + "/api/credits/balance"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("balance", 5)));

            budgetService.initBudget(USER_ID);

            // 10 rapid trigger fires, each consuming 1 credit from budget
            int passed = 0;
            for (int i = 0; i < 10; i++) {
                if (budgetService.tryConsumeOne(USER_ID)) {
                    passed++;
                }
            }

            assertThat(passed).isEqualTo(5);
        }
    }
}
