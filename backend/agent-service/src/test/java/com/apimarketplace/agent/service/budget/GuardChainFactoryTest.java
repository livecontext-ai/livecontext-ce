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
}
