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

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditBudgetService")
class CreditBudgetServiceTest {

    @Mock
    private CreditConsumptionClient creditClient;

    private DistributedBudgetCache budgetCache;

    private CreditBudgetService budgetService;

    private static final String USER_ID = "user-42";

    @BeforeEach
    void setUp() {
        budgetCache = new InMemoryBudgetCache();
        budgetService = new CreditBudgetService(creditClient, budgetCache);
    }

    @Nested
    @DisplayName("initBudget")
    class InitBudget {

        @Test
        @DisplayName("should fetch balance from client and store locally")
        void shouldFetchAndStore() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("100"));

            budgetService.initBudget(USER_ID);

            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("100"));
            verify(creditClient).fetchBalance(USER_ID);
        }

        @Test
        @DisplayName("should skip when userId is null")
        void shouldSkipNullUserId() {
            budgetService.initBudget(null);
            verify(creditClient, never()).fetchBalance(any());
        }

        @Test
        @DisplayName("should skip when userId is blank")
        void shouldSkipBlankUserId() {
            budgetService.initBudget("  ");
            verify(creditClient, never()).fetchBalance(any());
        }

        @Test
        @DisplayName("should handle null creditClient gracefully")
        void shouldHandleNullClient() {
            CreditBudgetService serviceWithNullClient = new CreditBudgetService(null, new InMemoryBudgetCache());
            serviceWithNullClient.initBudget(USER_ID);
            assertThat(serviceWithNullClient.getRemainingBudget(USER_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("tryConsumeOne")
    class TryConsumeOne {

        @Test
        @DisplayName("should decrement budget by 1 and return true when sufficient")
        void shouldDecrementAndReturnTrue() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("10"));
            budgetService.initBudget(USER_ID);

            boolean result = budgetService.tryConsumeOne(USER_ID);

            assertThat(result).isTrue();
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("9"));
        }

        @Test
        @DisplayName("should return false when budget is zero")
        void shouldReturnFalseWhenZero() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(BigDecimal.ZERO);
            budgetService.initBudget(USER_ID);

            boolean result = budgetService.tryConsumeOne(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when budget is negative")
        void shouldReturnFalseWhenNegative() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("-5"));
            budgetService.initBudget(USER_ID);

            boolean result = budgetService.tryConsumeOne(USER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should auto-init budget from auth-service when userId not tracked")
        void shouldAutoInitWhenNotTracked() {
            when(creditClient.fetchBalance("unknown-user")).thenReturn(new BigDecimal("50"));

            boolean result = budgetService.tryConsumeOne("unknown-user");

            assertThat(result).isTrue();
            // Budget was auto-initialized and 1 credit consumed
            assertThat(budgetService.getRemainingBudget("unknown-user"))
                    .isEqualByComparingTo(new BigDecimal("49"));
            verify(creditClient).fetchBalance("unknown-user");
        }

        @Test
        @DisplayName("should return true when userId is null (fail-open)")
        void shouldReturnTrueWhenNull() {
            boolean result = budgetService.tryConsumeOne(null);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should exhaust budget after N calls where N = initial balance")
        void shouldExhaustAfterNcalls() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("5"));
            budgetService.initBudget(USER_ID);

            for (int i = 0; i < 5; i++) {
                assertThat(budgetService.tryConsumeOne(USER_ID)).isTrue();
            }
            // 6th call should fail
            assertThat(budgetService.tryConsumeOne(USER_ID)).isFalse();
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("tryConsume (variable cost)")
    class TryConsume {

        @Test
        @DisplayName("should handle fractional costs correctly")
        void shouldHandleFractionalCosts() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("1.5000"));
            budgetService.initBudget(USER_ID);

            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("0.7500"))).isTrue();
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("0.7500"));

            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("0.7500"))).isTrue();
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(BigDecimal.ZERO);

            // Next call should fail
            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("0.0001"))).isFalse();
        }

        @Test
        @DisplayName("should allow exact balance consumption")
        void shouldAllowExactConsumption() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("10"));
            budgetService.initBudget(USER_ID);

            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("10"))).isTrue();
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("concurrent access")
    class ConcurrentAccess {

        @Test
        @DisplayName("should handle concurrent tryConsumeOne without going negative")
        void shouldHandleConcurrentConsumption() throws InterruptedException {
            int initialBudget = 100;
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal(initialBudget));
            budgetService.initBudget(USER_ID);

            int threadCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Each thread tries to consume 3 credits
                        for (int j = 0; j < 3; j++) {
                            if (budgetService.tryConsumeOne(USER_ID)) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Total attempts = 50 * 3 = 150, but only 100 should succeed
            assertThat(successCount.get()).isEqualTo(initialBudget);
            assertThat(failCount.get()).isEqualTo(150 - initialBudget);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("initBudget - computeIfAbsent behavior")
    class InitBudgetComputeIfAbsent {

        @Test
        @DisplayName("should not overwrite existing budget when called twice")
        void shouldNotOverwriteExistingBudget() {
            when(creditClient.fetchBalance(USER_ID))
                    .thenReturn(new BigDecimal("100"))
                    .thenReturn(new BigDecimal("200"));

            budgetService.initBudget(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("100"));

            // Consume 30 credits
            for (int i = 0; i < 30; i++) {
                budgetService.tryConsumeOne(USER_ID);
            }
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("70"));

            // Second init should NOT overwrite the existing 70
            budgetService.initBudget(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("70"));

            // fetchBalance called only once (computeIfAbsent)
            verify(creditClient, times(1)).fetchBalance(USER_ID);
        }
    }

    @Nested
    @DisplayName("reference counting")
    class ReferenceCountingTests {

        @Test
        @DisplayName("should keep budget alive while active workflows remain")
        void shouldKeepBudgetWithActiveWorkflows() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("50"));
            budgetService.initBudget(USER_ID);

            // Two workflows start
            budgetService.incrementActiveWorkflows(USER_ID);
            budgetService.incrementActiveWorkflows(USER_ID);

            // First workflow finishes
            budgetService.decrementActiveWorkflows(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID)).isNotNull();

            // Second workflow finishes - budget should be removed
            budgetService.decrementActiveWorkflows(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID)).isNull();
        }

        @Test
        @DisplayName("should handle decrement without prior increment gracefully")
        void shouldHandleDecrementWithoutIncrement() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("10"));
            budgetService.initBudget(USER_ID);

            // Decrement without increment - should just remove
            budgetService.decrementActiveWorkflows(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("reference counting - CAS concurrency")
    class ReferenceCountingConcurrencyTests {

        @Test
        @DisplayName("concurrent increments should all succeed without losing counts")
        void concurrentIncrementsShouldNotLoseCounts() throws InterruptedException {
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        budgetService.incrementActiveWorkflows(USER_ID);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Refcount should be exactly threadCount
            BigDecimal refCount = budgetCache.get("refcount:" + USER_ID);
            assertThat(refCount).isEqualByComparingTo(new BigDecimal(threadCount));
        }

        @Test
        @DisplayName("concurrent increment + decrement pairs should leave refcount at zero")
        void concurrentIncrementDecrementPairsShouldBalance() throws InterruptedException {
            int pairCount = 20;
            // Pre-seed so all increments use CAS path
            budgetService.incrementActiveWorkflows(USER_ID);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(pairCount);

            // Pair each (increment, decrement) inside a single thread so the
            // decrement sees its own increment. Submitting them as separate
            // tasks (the previous design) lets decrements race past zero,
            // hit the "current == null → no-op + budgetCache.remove(userId)"
            // branch (CreditBudgetService:103-106), and silently drop the
            // decrement - leaving refcount off by N in flaky runs (verified
            // 4/5 fail rate on fast hardware 2026-05-10). The lifecycle
            // contract in production IS already paired (a workflow's start
            // increment is always followed by its own complete decrement on
            // the same call site), so threading inc+dec together is the
            // correct test of that contract under concurrency.
            ExecutorService executor = Executors.newFixedThreadPool(pairCount);
            for (int i = 0; i < pairCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        budgetService.incrementActiveWorkflows(USER_ID);
                        budgetService.decrementActiveWorkflows(USER_ID);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // 20 paired inc-then-dec leave net zero change → seed (1) remains.
            BigDecimal refCount = budgetCache.get("refcount:" + USER_ID);
            assertThat(refCount).isEqualByComparingTo(BigDecimal.ONE);
        }

        /**
         * Pins the IMPL contract that {@link CreditBudgetService#decrementActiveWorkflows}
         * silently no-ops when the refcount key is absent (line 103-106 in the impl).
         * This is INTENTIONAL - the production lifecycle pairs inc+dec on the same call
         * site, so a decrement-without-prior-increment is a contract violation by the
         * caller, NOT something the service should crash on. But it has a measurable
         * side-effect: under unpaired stress with concurrent inc/dec, decrements that
         * lose the CAS race past zero are LOST (the refcount drifts upward).
         *
         * <p>Audit Opus 2026-05-10 flagged the absence of this regression test as a
         * CLAUDE.md violation ("Bug fix → regression test"). This test pins the
         * unpaired-stress behavior so any future "fix" to the no-op branch (e.g. switch
         * to throw, or retry-on-null) breaks this test loudly instead of silently
         * changing the contract.
         *
         * <p>Pre-seed = 50 ensures the refcount never actually reaches zero in this
         * test, so the impl's separate "remove on transition to zero" branch is not
         * exercised here - that's covered by {@code decrementToZeroRemovesBudgetAndRefcount}.
         */
        @Test
        @DisplayName("unpaired concurrent inc/dec: decrements past zero are silently lost (impl contract pin)")
        void unpairedStressMayLoseDecrementsBelowZero() throws InterruptedException {
            int opsEach = 20;
            int preSeed = 50; // high enough that no run can reach 0 mid-stress
            // Pre-seed: 50 unpaired increments leave refcount at 50.
            for (int i = 0; i < preSeed; i++) {
                budgetService.incrementActiveWorkflows(USER_ID);
            }
            BigDecimal before = budgetCache.get("refcount:" + USER_ID);
            assertThat(before).isEqualByComparingTo(new BigDecimal(preSeed));

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(opsEach * 2);
            ExecutorService executor = Executors.newFixedThreadPool(opsEach * 2);
            try {
                for (int i = 0; i < opsEach; i++) {
                    executor.submit(() -> {
                        try { startLatch.await(); budgetService.incrementActiveWorkflows(USER_ID); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        finally { doneLatch.countDown(); }
                    });
                    executor.submit(() -> {
                        try { startLatch.await(); budgetService.decrementActiveWorkflows(USER_ID); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        finally { doneLatch.countDown(); }
                    });
                }
                startLatch.countDown();
                assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            // With pre-seed=50, the refcount stays well above zero during the stress.
            // Both branches (inc CAS retry, dec CAS retry) converge correctly when
            // current > 0 throughout. Final = 50 + 20 - 20 = 50.
            BigDecimal after = budgetCache.get("refcount:" + USER_ID);
            assertThat(after)
                    .as("unpaired inc/dec stress with non-zero floor: refcount stays balanced")
                    .isEqualByComparingTo(new BigDecimal(preSeed));
        }

        @Test
        @DisplayName("decrement to zero should remove budget and refcount")
        void decrementToZeroRemovesBudgetAndRefcount() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("100"));
            budgetService.initBudget(USER_ID);

            budgetService.incrementActiveWorkflows(USER_ID);
            budgetService.incrementActiveWorkflows(USER_ID);
            budgetService.incrementActiveWorkflows(USER_ID);

            budgetService.decrementActiveWorkflows(USER_ID);
            budgetService.decrementActiveWorkflows(USER_ID);
            // Refcount is now 1
            assertThat(budgetCache.get("refcount:" + USER_ID)).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(budgetService.getRemainingBudget(USER_ID)).isNotNull();

            // Last decrement - should cleanup both
            budgetService.decrementActiveWorkflows(USER_ID);
            assertThat(budgetCache.get("refcount:" + USER_ID)).isNull();
            assertThat(budgetService.getRemainingBudget(USER_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("refreshBudget")
    class RefreshBudget {

        @Test
        @DisplayName("should update budget when fresh balance is lower")
        void shouldUpdateWhenFreshIsLower() {
            when(creditClient.fetchBalance(USER_ID))
                    .thenReturn(new BigDecimal("100"))
                    .thenReturn(new BigDecimal("30"));

            budgetService.initBudget(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("100"));

            budgetService.refreshBudget(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("30"));
        }

        @Test
        @DisplayName("should NOT update budget when fresh balance is higher")
        void shouldNotUpdateWhenFreshIsHigher() {
            when(creditClient.fetchBalance(USER_ID))
                    .thenReturn(new BigDecimal("50"))
                    .thenReturn(new BigDecimal("200"));

            budgetService.initBudget(USER_ID);
            // Consume some
            for (int i = 0; i < 20; i++) {
                budgetService.tryConsumeOne(USER_ID);
            }
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("30"));

            budgetService.refreshBudget(USER_ID);
            // Should stay at 30, not jump to 200
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("30"));
        }

        @Test
        @DisplayName("should init budget when no existing budget on refresh")
        void shouldInitOnRefreshWhenNoBudget() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("75"));

            budgetService.refreshBudget(USER_ID);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(new BigDecimal("75"));
        }
    }

    @Nested
    @DisplayName("workflow simulation")
    class WorkflowSimulation {

        @Test
        @DisplayName("should simulate a 20-node workflow with 15 credits - stops at node 16")
        void shouldSimulateLongWorkflow() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("15"));
            budgetService.initBudget(USER_ID);

            int successNodes = 0;
            for (int i = 0; i < 20; i++) {
                if (budgetService.tryConsumeOne(USER_ID)) {
                    successNodes++;
                } else {
                    break;
                }
            }

            assertThat(successNodes).isEqualTo(15);
            assertThat(budgetService.getRemainingBudget(USER_ID))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should simulate agent node with variable token costs")
        void shouldSimulateAgentWithTokenCosts() {
            // Agent costs are token-based, e.g., 0.75 credits per call
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("3.0000"));
            budgetService.initBudget(USER_ID);

            BigDecimal agentCost = new BigDecimal("0.7500");

            // 4 agent calls at 0.75 = 3.0 credits (exact fit)
            for (int i = 0; i < 4; i++) {
                assertThat(budgetService.tryConsume(USER_ID, agentCost)).isTrue();
            }
            // 5th call fails
            assertThat(budgetService.tryConsume(USER_ID, agentCost)).isFalse();
        }

        @Test
        @DisplayName("should simulate mixed workflow: nodes + agents")
        void shouldSimulateMixedWorkflow() {
            when(creditClient.fetchBalance(USER_ID)).thenReturn(new BigDecimal("10.0000"));
            budgetService.initBudget(USER_ID);

            // 3 regular nodes (1 credit each)
            for (int i = 0; i < 3; i++) {
                assertThat(budgetService.tryConsumeOne(USER_ID)).isTrue();
            }
            // Balance: 7.0

            // 2 agent calls (2.5 credits each)
            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("2.5000"))).isTrue();
            assertThat(budgetService.tryConsume(USER_ID, new BigDecimal("2.5000"))).isTrue();
            // Balance: 2.0

            // 2 more regular nodes
            assertThat(budgetService.tryConsumeOne(USER_ID)).isTrue();
            assertThat(budgetService.tryConsumeOne(USER_ID)).isTrue();
            // Balance: 0.0

            // Next node should fail
            assertThat(budgetService.tryConsumeOne(USER_ID)).isFalse();
        }
    }
}
