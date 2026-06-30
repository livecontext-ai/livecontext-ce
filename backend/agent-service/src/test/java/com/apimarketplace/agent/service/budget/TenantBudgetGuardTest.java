package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TenantBudgetGuard")
@ExtendWith(MockitoExtension.class)
class TenantBudgetGuardTest {

    @Mock
    private CreditConsumptionClient creditClient;

    private static final ModelCostCalculator CHEAP_RATES = new ModelCostCalculator(
        new BigDecimal("0.001"), new BigDecimal("0.003"), BigDecimal.ZERO);

    private static IterationContext ctx(String tenantId, int upcoming, long prompt, long completion) {
        return new IterationContext(
            tenantId, "agent-1", "openai", "gpt-test",
            upcoming, upcoming - 1, prompt, completion, 100L);
    }

    @Test
    @DisplayName("allows when tenant id is null")
    void nullTenant() {
        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        assertThat(guard.check(ctx(null, 1, 0, 0)).proceed()).isTrue();
    }

    @Test
    @DisplayName("allows when tenant id is blank")
    void blankTenant() {
        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        assertThat(guard.check(ctx("", 1, 0, 0)).proceed()).isTrue();
    }

    @Test
    @DisplayName("denies when tenant balance is zero")
    void zeroBalance() {
        when(creditClient.fetchBalance("tenant-1")).thenReturn(BigDecimal.ZERO);

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        GuardResult result = guard.check(ctx("tenant-1", 1, 0, 0));

        assertThat(result.proceed()).isFalse();
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        assertThat(result.scope()).isEqualTo("tenant");
    }

    @Test
    @DisplayName("allows when tenant balance covers projected cost")
    void allowsWhenAffordable() {
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("100"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        GuardResult result = guard.check(ctx("tenant-1", 1, 0, 0));

        assertThat(result.proceed()).isTrue();
    }

    @Test
    @DisplayName("denies when projected total cost exceeds balance")
    void deniesWhenProjectionExceedsBalance() {
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("0.10"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        // After 5 iters of 50000 prompt / 25000 completion total:
        // cost = 0.001*50000/1000 + 0.003*25000/1000 = 0.05 + 0.075 = 0.125 > 0.10
        GuardResult result = guard.check(ctx("tenant-1", 6, 50_000, 25_000));

        assertThat(result.proceed()).isFalse();
        assertThat(result.scope()).isEqualTo("tenant");
    }

    @Test
    @DisplayName("caches balance across iterations")
    void cachesBalance() {
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("100"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        for (int i = 1; i <= 4; i++) {
            assertThat(guard.check(ctx("tenant-1", i, 100, 50)).proceed()).isTrue();
        }
        // First call hits the client; subsequent 3 hit the cache.
        verify(creditClient, times(1)).fetchBalance("tenant-1");
    }

    @Test
    @DisplayName("refreshes balance after the configured number of iterations")
    void refreshesBalance() {
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("100"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, CHEAP_RATES);
        for (int i = 1; i <= 8; i++) {
            guard.check(ctx("tenant-1", i, 100, 50));
        }
        // First call + one refresh after 5 iterations = 2 calls.
        verify(creditClient, times(2)).fetchBalance(anyString());
    }
}
