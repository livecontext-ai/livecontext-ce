package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GuardChainFactory}, focused on the centralized pricing
 * integration via {@link PricingSnapshotClient}.
 */
@DisplayName("GuardChainFactory")
@ExtendWith(MockitoExtension.class)
class GuardChainFactoryTest {

    @Mock private CreditConsumptionClient creditConsumptionClient;
    @Mock private BudgetResolver budgetResolver;
    @Mock private PricingSnapshotClient pricingSnapshotClient;

    private GuardChainFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GuardChainFactory(
            creditConsumptionClient, budgetResolver, pricingSnapshotClient);
    }

    @Nested
    @DisplayName("resolveCalculator()")
    class ResolveCalculatorTests {

        @Test
        @DisplayName("returns real rates when model found in snapshot (V80 scale: USD per 1M tokens)")
        void realRatesFromSnapshot() {
            // V80: claude-sonnet-4-6 is stored as 3.00 input / 15.00 output (USD per 1M tokens).
            when(pricingSnapshotClient.getRates("anthropic", "claude-sonnet-4-6"))
                .thenReturn(Optional.of(new PricingRates(
                    new BigDecimal("3.00"), new BigDecimal("15.00"), BigDecimal.ZERO)));

            ModelCostCalculator calc = factory.resolveCalculator("anthropic", "claude-sonnet-4-6");

            assertThat(calc.inputRate()).isEqualByComparingTo("3.00");
            assertThat(calc.outputRate()).isEqualByComparingTo("15.00");
            assertThat(calc.fixedCost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("returns PESSIMISTIC opus-class fallback when model NOT in snapshot (bug #1)")
        void pessimisticFallbackWhenMissing() {
            when(pricingSnapshotClient.getRates(anyString(), anyString()))
                .thenReturn(Optional.empty());

            ModelCostCalculator calc = factory.resolveCalculator("anthropic", "unknown-model");

            // Bug #1: fallback MUST be pessimistic (opus-class 15/75 per 1M) so unknown
            // models trip the budget guard quickly instead of silently bypassing it.
            // Previous broken fallback was 0.015/0.075 (1000x too low on V80 scale).
            assertThat(calc.inputRate()).isEqualByComparingTo("15.0");
            assertThat(calc.outputRate()).isEqualByComparingTo("75.0");
            assertThat(calc.isZero()).isFalse();
        }

        @Test
        @DisplayName("returns zero-cost calculator when provider is null")
        void zeroCostWhenProviderNull() {
            ModelCostCalculator calc = factory.resolveCalculator(null, "claude-sonnet-4-6");

            assertThat(calc.inputRate()).isEqualByComparingTo("0");
            assertThat(calc.outputRate()).isEqualByComparingTo("0");
            verify(pricingSnapshotClient, never()).getRates(any(), any());
        }

        @Test
        @DisplayName("resolves agent budget through transactional resolver when org scope is bound")
        void resolvesAgentBudgetThroughTransactionalResolverWhenOrgScopeIsBound() {
            UUID agentId = UUID.randomUUID();
            when(pricingSnapshotClient.getRates("openai", "gpt-5-mini")).thenReturn(Optional.empty());
            when(budgetResolver.resolveAndPersistForAgent(eq(agentId), eq("org-1"), any()))
                .thenReturn(BudgetState.disabled());

            TenantResolver.runWithOrgScope("org-1",
                    () -> factory.forAgent("tenant-1", agentId.toString(), "openai", "gpt-5-mini"));

            verify(budgetResolver).resolveAndPersistForAgent(eq(agentId), eq("org-1"), any());
        }

        @Test
        @DisplayName("returns zero-cost calculator when model is null")
        void zeroCostWhenModelNull() {
            ModelCostCalculator calc = factory.resolveCalculator("anthropic", null);

            assertThat(calc.inputRate()).isEqualByComparingTo("0");
            verify(pricingSnapshotClient, never()).getRates(any(), any());
        }
    }

    @Nested
    @DisplayName("forAgent()")
    class ForAgentTests {

        @Test
        @DisplayName("4-arg overload passes provider/model to resolveCalculator")
        void fourArgPassesProviderModel() {
            // V80: gpt-5-mini is 0.25 / 2.00 USD per 1M tokens.
            when(pricingSnapshotClient.getRates("openai", "gpt-5-mini"))
                .thenReturn(Optional.of(new PricingRates(
                    new BigDecimal("0.25"), new BigDecimal("2.00"), BigDecimal.ZERO)));
            lenient().when(creditConsumptionClient.checkCredits(anyString())).thenReturn(true);

            PreIterationGuard guard = factory.forAgent("tenant-1", null, "openai", "gpt-5-mini");

            assertThat(guard).isNotNull();
            verify(pricingSnapshotClient).getRates("openai", "gpt-5-mini");
        }

        @Test
        @DisplayName("2-arg backward-compatible overload uses null provider/model")
        void twoArgUsesNullProviderModel() {
            PreIterationGuard guard = factory.forAgent("tenant-1", null);

            assertThat(guard).isNotNull();
            // null provider/model → never calls pricingSnapshotClient
            verify(pricingSnapshotClient, never()).getRates(any(), any());
        }
    }

    @Nested
    @DisplayName("forAgentWithFallback()")
    class ForAgentWithFallbackTests {

        @Test
        @DisplayName("6-arg overload passes provider/model and budget fallback")
        void sixArgPassesAll() {
            // V80: claude-opus-4-6 is 5.00 / 25.00 USD per 1M tokens.
            when(pricingSnapshotClient.getRates("anthropic", "claude-opus-4-6"))
                .thenReturn(Optional.of(new PricingRates(
                    new BigDecimal("5.00"), new BigDecimal("25.00"), BigDecimal.ZERO)));

            PreIterationGuard guard = factory.forAgentWithFallback(
                "tenant-1", null, 10.0, 0.0, "anthropic", "claude-opus-4-6");

            assertThat(guard).isNotNull();
            verify(pricingSnapshotClient).getRates("anthropic", "claude-opus-4-6");
        }

        @Test
        @DisplayName("4-arg backward-compatible overload uses null provider/model")
        void fourArgUsesNullProviderModel() {
            PreIterationGuard guard = factory.forAgentWithFallback(
                "tenant-1", null, 10.0, 0.0);

            assertThat(guard).isNotNull();
            verify(pricingSnapshotClient, never()).getRates(any(), any());
        }
    }

    @Nested
    @DisplayName("bridgeBudget() - LC-056, the budget a CLI bridge run enforces itself")
    class BridgeBudgetTests {

        private final String agentId = UUID.randomUUID().toString();

        @Test
        @DisplayName("the tenant balance on the BILLED model and the agent budget window")
        void balanceAndAgentWindow() {
            when(creditConsumptionClient.fetchLlmSpendableBalance("tenant-1", "anthropic", "claude-sonnet-4-6"))
                .thenReturn(new BigDecimal("70.5"));
            when(budgetResolver.resolveAndPersistForAgent(eq(UUID.fromString(agentId)), any(), any()))
                .thenReturn(new BudgetState(new BigDecimal("500"), new BigDecimal("120"), BigDecimal.ZERO, false));

            GuardChainFactory.BridgeBudget budget =
                factory.bridgeBudget("tenant-1", agentId, "anthropic", "claude-sonnet-4-6");

            assertThat(budget.tenantBalance()).isEqualTo(70.5);
            assertThat(budget.maxCreditBudget()).isEqualTo(500.0);
            assertThat(budget.creditsConsumedSoFar()).isEqualTo(120.0);
        }

        @Test
        @DisplayName("an agent with no budget configured, or no agent, sends no agent budget")
        void noAgentBudget() {
            when(creditConsumptionClient.fetchLlmSpendableBalance(any(), any(), any())).thenReturn(BigDecimal.TEN);
            when(budgetResolver.resolveAndPersistForAgent(any(), any(), any())).thenReturn(BudgetState.disabled());

            assertThat(factory.bridgeBudget("tenant-1", agentId, "p", "m").maxCreditBudget()).isNull();
            assertThat(factory.bridgeBudget("tenant-1", null, "p", "m").maxCreditBudget()).isNull();
            assertThat(factory.bridgeBudget("tenant-1", "not-a-uuid", "p", "m").maxCreditBudget()).isNull();
        }

        @Test
        @DisplayName("without a credit client or a tenant there is no platform balance (null, never 0)")
        void noClientNoBalance() {
            GuardChainFactory noCredit = new GuardChainFactory(null, budgetResolver, pricingSnapshotClient);

            assertThat(noCredit.bridgeBudget("tenant-1", null, "p", "m").tenantBalance()).isNull();
            assertThat(factory.bridgeBudget(null, null, "p", "m").tenantBalance()).isNull();
        }

        @Test
        @DisplayName("credits reserved by in-flight sub-agents count as consumed, as in the Java loop's agent guard")
        void reservedCreditsCountAsConsumed() {
            when(budgetResolver.resolveAndPersistForAgent(eq(UUID.fromString(agentId)), any(), any()))
                .thenReturn(new BudgetState(new BigDecimal("500"), new BigDecimal("120"), new BigDecimal("80"), false));

            assertThat(factory.bridgeBudget("tenant-1", agentId, "p", "m").creditsConsumedSoFar()).isEqualTo(200.0);
        }

        @Test
        @DisplayName("a failing agent-budget lookup sends no agent budget, and keeps the balance, rather than failing the run")
        void failingAgentBudgetLookupKeepsTheBalance() {
            when(creditConsumptionClient.fetchLlmSpendableBalance(any(), any(), any())).thenReturn(BigDecimal.TEN);
            when(budgetResolver.resolveAndPersistForAgent(any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

            GuardChainFactory.BridgeBudget budget = factory.bridgeBudget("tenant-1", agentId, "p", "m");

            assertThat(budget.maxCreditBudget()).isNull();
            assertThat(budget.tenantBalance()).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("BridgeBudget.exhaustedScope() - what a bridge run cannot start under")
    class ExhaustedScopeTests {

        private String scope(Double balance, Double budget, Double consumed) {
            return new GuardChainFactory.BridgeBudget(balance, budget, consumed).exhaustedScope();
        }

        @Test
        @DisplayName("regression: an agent budget of 0 is 'no budget', never a spent one")
        void zeroAgentBudgetIsNoBudget() {
            // AgentContextBuilder forwards a creditBudget of 0 as-is; everywhere else 0 means off.
            assertThat(scope(50.0, 0.0, 0.0)).isNull();
            assertThat(scope(50.0, -5.0, 3.0)).isNull();
        }

        @Test
        @DisplayName("an agent budget is spent exactly at its limit, not before")
        void agentBudgetBoundary() {
            assertThat(scope(50.0, 100.0, 99.99)).isNull();
            assertThat(scope(50.0, 100.0, 100.0)).isEqualTo("agent");
        }

        @Test
        @DisplayName("a balance at or below 0 is refused; an unknown balance or budget refuses nothing")
        void balanceAndUnknownSides() {
            assertThat(scope(0.0, null, null)).isEqualTo("tenant");
            assertThat(scope(-1.0, 100.0, 0.0)).isEqualTo("tenant");
            assertThat(scope(null, null, null)).isNull();
            assertThat(scope(null, 100.0, null)).isNull();
            assertThat(scope(null, null, 100.0)).isNull();
        }

        @Test
        @DisplayName("the refusal names the scope it refuses")
        void refusalMessageNamesTheScope() {
            assertThat(new GuardChainFactory.BridgeBudget(10.0, 100.0, 100.0).refusalMessage("p", "m"))
                .startsWith("Agent credit budget exhausted");
            assertThat(new GuardChainFactory.BridgeBudget(0.0, null, null).refusalMessage("anthropic", "x"))
                .startsWith("Insufficient credits").contains("anthropic/x")
                // A balance the auth service could not answer arrives as 0: the message says so.
                .contains("could not be read counts as 0");
        }
    }
}
