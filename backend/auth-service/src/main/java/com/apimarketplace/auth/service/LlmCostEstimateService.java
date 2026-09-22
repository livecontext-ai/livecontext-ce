package com.apimarketplace.auth.service;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.common.credit.ModelTier;
import com.apimarketplace.common.plan.PlanFeatureKeys;
import com.apimarketplace.common.web.AppEditionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes what a client needs to show a pre-flight credit estimate next to a
 * model, and nothing else.
 *
 * <h2>Two coefficients, not a multiplier and a token count</h2>
 * The obvious payload would be the billing multiplier plus each profile's token
 * workload, and the client would multiply them out. It would also put a field
 * called "multiplier" holding the platform's gross margin into a response any
 * reader can open in a browser's network tab. So the two are folded together
 * before they leave: each profile ships the coefficient its rate is multiplied
 * by, and the margin is not a labelled value anywhere on the wire.
 *
 * <p>This is presentation, not secrecy. The number the user is shown IS the
 * credits they will be charged, and the ledger states the tokens and the model
 * for every debit. What the payload avoids is handing over a named margin.
 *
 * <h2>The contract</h2>
 * {@code credits = inputRate x inputCoefficient + cacheWriteRate x
 * cacheWriteCoefficient + cacheReadRate x cacheReadCoefficient + outputRate x
 * outputCoefficient}, with rates in USD per 1M tokens. That is exactly
 * {@link ModelPricingService#calculateCost} over the profile's four DISJOINT
 * token classes, which {@code LlmCostProfileFormulaTest} pins: the estimate a
 * picker renders is the figure the ledger will debit, not an approximation.
 *
 * <p>There were two coefficients until 2026-09-16, because the profiles carried
 * no cache tokens. That made the picker quote every cached token at the full
 * input rate, and on Anthropic a cache WRITE costs 1.25x input while a read costs
 * a fortieth of it, so the quote came out at about half of a real message. The
 * two extra coefficients are what let a model be priced by its OWN published
 * cache rates, which V491 put in the catalogue.
 *
 * <p>Two rules complete it, and both are published rather than left to the
 * client to invent. A model whose catalogue entry has no cache price resolves it
 * through {@code cacheFallback}, the same provider-family weights this service's
 * billing side falls back to; a stored rate of zero counts as NO price on both
 * sides, because a cached token is never free. A model that does not cache AT ALL
 * is priced with every prompt token at its input rate: the same FORM as the old
 * two-coefficient contract, though not the same figure, since the profile now
 * describes a whole agent context rather than a plain chat. A profile with zero
 * cache tokens (guardrail, classify) is unaffected either way: its two cache
 * coefficients are zero, so the extra terms vanish.
 */
@Service
public class LlmCostEstimateService {

    /** Rates are USD per 1M tokens; credits are USD x 1000. */
    private static final BigDecimal RATE_SCALE = new BigDecimal("1000");

    /** Enough precision that the cheapest profile on the cheapest model still moves. */
    private static final int COEFFICIENT_SCALE = 8;

    private static final Logger log = LoggerFactory.getLogger(LlmCostEstimateService.class);

    private final ModelPricingService pricingService;
    private final CreditService creditService;
    private final CredentialRepository credentialRepository;
    private final PlanFeatureRequirementService planFeatureRequirementService;
    private final PlanLimitService planLimitService;
    private final AppEditionProvider editionProvider;

    /** Flat own-key fee per tier; absent, the basis carries no own-key block. */
    @Autowired(required = false)
    private OwnKeyTurnPricing ownKeyTurnPricing;

    public LlmCostEstimateService(ModelPricingService pricingService, CreditService creditService,
                                  @Nullable CredentialRepository credentialRepository,
                                  @Nullable PlanFeatureRequirementService planFeatureRequirementService,
                                  @Nullable PlanLimitService planLimitService,
                                  @Nullable AppEditionProvider editionProvider) {
        this.credentialRepository = credentialRepository;
        this.pricingService = pricingService;
        this.creditService = creditService;
        this.planFeatureRequirementService = planFeatureRequirementService;
        this.planLimitService = planLimitService;
        this.editionProvider = editionProvider;
    }

    /**
     * The estimate basis, or a disabled answer where credits are not metered.
     *
     * <p>An install that does not meter (CE) has no margin, no wallet and nothing
     * to estimate, so it gets {@code enabled=false} and no coefficients at all
     * rather than a number that would mean nothing there.
     */
    public Map<String, Object> buildBasis() {
        return buildBasis(null);
    }

    /**
     * @param tenantId the caller, or null when unknown. Present, the basis also carries the
     *                 providers whose own key would serve them and the flat fee per tier, so
     *                 a picker can quote the exact number the ledger will charge on that
     *                 route instead of an estimate that route never pays. Absent or empty,
     *                 the block is omitted and every consumer falls back to the estimate.
     */
    public Map<String, Object> buildBasis(String tenantId) {
        if (creditService.isUnlimited()) {
            return Map.of("enabled", false, "profiles", Map.of());
        }

        BigDecimal multiplier = pricingService.getCloudLlmBillingMultiplier();
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (LlmCostProfile profile : LlmCostProfile.values()) {
            profiles.put(profile.key(), Map.of(
                    "inputCoefficient", coefficient(profile.inputTokens(), multiplier),
                    "cacheWriteCoefficient", coefficient(profile.cacheWriteTokens(), multiplier),
                    "cacheReadCoefficient", coefficient(profile.cacheReadTokens(), multiplier),
                    "outputCoefficient", coefficient(profile.outputTokens(), multiplier)));
        }
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("enabled", true);
        basis.put("profiles", profiles);
        basis.put("cacheFallback", cacheFallback());
        // Per-provider correction to the coefficients above, which fold in the GLOBAL lever
        // and carry no provider. A provider billed on its own lever is absent from this map
        // unless it is overridden, and absent means 1, so the payload is empty on an install
        // that overrides nothing. Without it a picker quoting an overridden provider shows a
        // price the ledger does not debit.
        basis.put("providerScales", pricingService.getProviderBillingScales());
        // The own-key ladder as a PRICE LIST: what a turn on your own key costs, per tier,
        // whoever is asking. The `ownKey` block below answers a different question - whether
        // the NEXT call takes that route - and it is absent for everyone who has not saved a
        // key yet, which is precisely the reader deciding whether to save one. Published
        // separately so a settings panel can state the four numbers before the first key
        // exists, without inferring a route nobody is on.
        Map<String, Object> feeByTier = ownKeyFeeByTier();
        if (feeByTier != null) {
            basis.put("ownKeyFeeByTier", feeByTier);
        }
        Map<String, Object> ownKey = ownKeyBlock(tenantId);
        if (ownKey != null) {
            basis.put("ownKey", ownKey);
        }
        return basis;
    }

    /**
     * The flat fee per turn for each tier, or null on an install that wires no ladder.
     *
     * <p>One method feeding both the price list and the {@code ownKey} block, so the number a
     * panel advertises and the number a model row quotes cannot drift apart. Two maps are built
     * when both are present, not one shared instance - what rules out divergence is that every
     * value comes from the same {@link OwnKeyTurnPricing} bean, not that the callers share an
     * object. The equality of the two is pinned by a test, which is the property that matters.
     */
    private Map<String, Object> ownKeyFeeByTier() {
        if (ownKeyTurnPricing == null) {
            return null;
        }
        Map<String, Object> fees = new LinkedHashMap<>();
        for (ModelTier tier : ModelTier.values()) {
            fees.put(tier.key(), ownKeyTurnPricing.creditsPerTurn(tier));
        }
        return fees;
    }

    /**
     * The own-key facts, or null when the caller has no key that would serve, or their plan
     * does not let it serve.
     *
     * <p><b>The plan is decided here, not by the client.</b> The client holds its own lock on
     * the same key, but it answers UNLOCKED while the plan map is loading AND when the request
     * for it fails, which is the right default for drawing a padlock and the wrong one for
     * quoting a price: a FREE account would then be shown the flat fee for a route
     * {@code KeyRouteResolver} pins to PLATFORM, under-quoting a top-tier turn by a factor of
     * about forty. This service reads the same table the run reads, so the quote and the route
     * agree by construction.
     */
    private Map<String, Object> ownKeyBlock(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || ownKeyTurnPricing == null || credentialRepository == null) {
            return null;
        }
        if (!ownKeyAllowedByPlan(tenantId)) {
            return null;
        }
        List<String> integrations;
        try {
            integrations = credentialRepository.findUsableLlmIntegrations(tenantId);
        } catch (Exception e) {
            // The estimate is worth more than the own-key refinement: quote the platform
            // number rather than fail the whole basis on a credential-table hiccup.
            log.warn("Own-key providers unreadable for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
        if (integrations.isEmpty()) {
            return null;
        }
        // A row that is not an llm_ integration is DROPPED, not published under its own name:
        // the query cannot return one today, and if that ever changes a wrong provider name
        // would quote the fee on models the key does not serve.
        List<String> providers = integrations.stream()
                .filter(i -> i != null && i.startsWith("llm_"))
                .map(i -> i.substring("llm_".length()))
                .filter(p -> !p.isBlank())
                .distinct()
                .toList();
        if (providers.isEmpty()) {
            return null;
        }
        // Read into a local and asserted here rather than relied on from a distance: the guard
        // that makes this non-null is the `ownKeyTurnPricing == null` early return at the top of
        // this method, and `Map.of` throws on a null value. One refactor that moves or relaxes
        // that guard turns a hot endpoint into an NPE, and the construction site would not show
        // why it was ever safe.
        Map<String, Object> fees = Objects.requireNonNull(
                ownKeyFeeByTier(), "ownKeyFeeByTier is non-null once ownKeyTurnPricing is present");
        return Map.of("providers", providers, "feeByTier", fees);
    }

    /**
     * Whether this tenant's plan lets their own key serve, on the same terms as the run.
     *
     * <p>Every branch matches {@code OwnKeyFeatureGate}, deliberately. Self-hosted and dedicated
     * cloud are NEVER gated there: they own their keys, the plan is a shared-cloud pricing
     * device, and both editions still meter credits, so without this branch the picker would go
     * silent about a fee the ledger charges (the {@code PRO} row V507 seeds exists on every
     * install, and those tenants often hold no subscription at all). An unreadable plan allows,
     * because the run allows too and the quote has to describe the run. An install with no plan
     * service wired cannot ask, and the run answers the same way.
     */
    private boolean ownKeyAllowedByPlan(String tenantId) {
        if (editionProvider != null && (editionProvider.isSelfHosted() || editionProvider.isDedicatedCloud())) {
            return true;
        }
        if (planFeatureRequirementService == null || planLimitService == null) {
            return true;
        }
        try {
            return planFeatureRequirementService.allows(
                    planLimitService.getPlanCode(tenantId), PlanFeatureKeys.OWN_LLM_KEY);
        } catch (Exception e) {
            log.warn("Plan gate unreadable for tenant {} - quoting the own-key fee: {}", tenantId, e.getMessage());
            return true;
        }
    }

    /**
     * How to resolve a cache rate the catalogue does not publish, one entry per billing
     * provider plus {@code "*"}.
     *
     * <p>Without it a client has one option for such a model - the full input rate - and
     * a chat profile spends most of its tokens on cache reads, the class that costs a
     * fortieth of input on Anthropic and half of it on OpenAI. The picker would quote up
     * to ten times the cheapest term and the ledger would debit the real figure, which is
     * a worse defect than the one the cache tokens were added to fix.
     *
     * <p>What this does and does not give away. The weights are cost-basis
     * approximations of what a provider charges for a cached token relative to a plain
     * one, set to the providers' own published ratios. They do NOT carry the margin:
     * that stays folded into the coefficients above and is not a named field anywhere on
     * this response. They ARE operator levers, so an install that moved one away from
     * the provider's true ratio would be publishing that choice - the accepted cost of a
     * browser resolving a rate the same way the ledger does.
     */
    private Map<String, Object> cacheFallback() {
        Map<String, Object> published = new LinkedHashMap<>();
        pricingService.cacheRateFallbacks().forEach((provider, fallback) -> published.put(provider, Map.of(
                "cacheWriteWeight", fallback.cacheWriteWeight(),
                "cacheReadWeight", fallback.cacheReadWeight(),
                "modelCacheWritePriceApplies", fallback.modelCacheWritePriceApplies())));
        return published;
    }

    private BigDecimal coefficient(int tokens, BigDecimal multiplier) {
        return BigDecimal.valueOf(tokens)
                .multiply(multiplier)
                .divide(RATE_SCALE, COEFFICIENT_SCALE, RoundingMode.HALF_UP);
    }
}
