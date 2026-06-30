package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Centralized factory for building {@link PreIterationGuard} chains.
 *
 * <p>Extracted from {@code AgentRemoteExecutionService} so that classify, guardrail,
 * and full agent executions all share the same guard construction logic. This ensures
 * budget enforcement is consistent across every LLM call path.</p>
 *
 * <p>Guard chain order: tenant guard runs first (macro budget), agent guard second
 * (micro budget). Tenant exhaustion makes agent budget moot.</p>
 *
 * <p>Model pricing rates come from {@link PricingSnapshotClient}, which caches the
 * auth-service pricing snapshot (the single source of truth). No hardcoded fallback
 * rates - if the snapshot is unavailable, a zero-cost calculator is used (fail-open
 * on cost estimation so the guard doesn't block based on stale hardcoded rates).</p>
 */
@Slf4j
@Component
public class GuardChainFactory {

    private final CreditConsumptionClient creditConsumptionClient;
    private final BudgetResolver budgetResolver;
    private final PricingSnapshotClient pricingSnapshotClient;

    public GuardChainFactory(
            CreditConsumptionClient creditConsumptionClient,
            BudgetResolver budgetResolver,
            PricingSnapshotClient pricingSnapshotClient) {
        this.creditConsumptionClient = creditConsumptionClient;
        this.budgetResolver = budgetResolver;
        this.pricingSnapshotClient = pricingSnapshotClient;
    }

    /**
     * Resolve model rates from the centralized pricing snapshot.
     *
     * @param provider LLM provider (e.g. "anthropic", "openai")
     * @param model    model identifier (e.g. "claude-sonnet-4-6")
     * @return calculator with real rates, or zero-cost if model not found
     */
    public ModelCostCalculator resolveCalculator(String provider, String model) {
        if (provider == null || model == null) {
            log.debug("No provider/model supplied - using zero-cost calculator for guard");
            return ModelCostCalculator.zero();
        }
        return pricingSnapshotClient.getRates(provider, model)
            .map(r -> new ModelCostCalculator(r.inputRate(), r.outputRate(), r.fixedCost(),
                r.contextWindow(), r.maxOutputTokens()))
            .orElseGet(() -> {
                // Bug #1: fallback must be PESSIMISTIC (over-estimate) so an unknown model
                // fails safe - the guard trips the budget quickly rather than letting a
                // mis-named model consume hundreds of credits before a timeout. Previously
                // used 0.015/0.075 (near-zero) which silently bypassed the budget guard.
                // We now use claude-3-opus-class rates (15/75 per 1M tokens) - the highest
                // tier in the pricing snapshot. Real consumption for most models will be
                // equal or cheaper, so unknown = over-estimate = safe.
                // V162: contextWindow / maxOutputTokens left null on the fallback so guards
                // detect "unknown model" and fall back to growth-only projection (or fail-
                // closed under the BUDGET_GUARD_REQUIRE_CTX_WINDOW flag, Phase 1C).
                log.warn("No pricing found for {}/{} in snapshot - guard will use PESSIMISTIC " +
                         "fallback rates (input=15, output=75 USD/1M tokens). " +
                         "Auth-service billing still uses real rates. " +
                         "Add this model to auth.model_pricing to get accurate projections.",
                         provider, model);
                return new ModelCostCalculator(
                    new BigDecimal("15.0"), new BigDecimal("75.0"), BigDecimal.ZERO, null, null);
            });
    }

    /**
     * Build a guard chain for a given tenant and (optional) agent entity.
     *
     * <p>When {@code agentEntityId} is non-null, {@link BudgetResolver} resolves the
     * agent budget inside its own transaction and honors weekly/monthly reset modes.
     * If the entity has no budget configured, only the tenant guard is used.</p>
     *
     * @param tenantId       tenant identifier (null disables tenant guard)
     * @param agentEntityId  agent entity UUID string (null disables agent guard)
     * @param provider       LLM provider for rate lookup
     * @param model          model identifier for rate lookup
     * @return composite guard, or {@link PreIterationGuard#ALWAYS_PROCEED} if both are disabled
     */
    public PreIterationGuard forAgent(String tenantId, String agentEntityId,
                                       String provider, String model) {
        ModelCostCalculator calculator = resolveCalculator(provider, model);

        TenantBudgetGuard tenantGuard = creditConsumptionClient != null
            ? new TenantBudgetGuard(creditConsumptionClient, calculator)
            : null;

        AgentBudgetGuard agentGuard = buildAgentGuard(agentEntityId, calculator);

        return PreIterationGuard.chain(tenantGuard, agentGuard);
    }

    /**
     * Backward-compatible overload - uses zero-cost calculator when provider/model
     * are not available at the call site. The tenant guard still enforces balance
     * checks via auth-service HTTP; only the per-iteration cost estimate is affected.
     */
    public PreIterationGuard forAgent(String tenantId, String agentEntityId) {
        return forAgent(tenantId, agentEntityId, null, null);
    }

    /**
     * Build a guard chain with DTO-level budget fallback (for chat conversations
     * that don't have a backing agent entity).
     */
    public PreIterationGuard forAgentWithFallback(String tenantId, String agentEntityId,
                                                   Double maxCreditBudget, Double creditsConsumedSoFar,
                                                   String provider, String model) {
        ModelCostCalculator calculator = resolveCalculator(provider, model);

        TenantBudgetGuard tenantGuard = creditConsumptionClient != null
            ? new TenantBudgetGuard(creditConsumptionClient, calculator)
            : null;

        AgentBudgetGuard agentGuard = buildAgentGuard(agentEntityId, calculator);

        // Fallback to DTO budget when no agent entity resolved
        if (agentGuard == null && maxCreditBudget != null && maxCreditBudget > 0) {
            BigDecimal budget = BigDecimal.valueOf(maxCreditBudget);
            BigDecimal consumed = creditsConsumedSoFar != null
                ? BigDecimal.valueOf(creditsConsumedSoFar)
                : BigDecimal.ZERO;
            agentGuard = new AgentBudgetGuard(budget, consumed, calculator);
        }

        return PreIterationGuard.chain(tenantGuard, agentGuard);
    }

    /**
     * Backward-compatible overload without provider/model.
     */
    public PreIterationGuard forAgentWithFallback(String tenantId, String agentEntityId,
                                                   Double maxCreditBudget, Double creditsConsumedSoFar) {
        return forAgentWithFallback(tenantId, agentEntityId, maxCreditBudget, creditsConsumedSoFar, null, null);
    }

    private AgentBudgetGuard buildAgentGuard(String agentEntityId, ModelCostCalculator calculator) {
        if (agentEntityId == null || budgetResolver == null) {
            return null;
        }
        try {
            UUID agentId = UUID.fromString(agentEntityId);
            String organizationId = TenantResolver.currentRequestOrganizationId();
            BudgetState state = budgetResolver.resolveAndPersistForAgent(agentId, organizationId, Instant.now());
            if (state.isEnabled()) {
                return new AgentBudgetGuard(
                    state.totalBudget(), state.consumedAfterReset(),
                    state.creditsReserved(), calculator);
            }
            return null; // entity missing or has no budget configured
        } catch (IllegalArgumentException e) {
            log.warn("Invalid agentEntityId '{}', agent guard disabled", agentEntityId);
        }
        return null;
    }
}
