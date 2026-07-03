package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    private static final BigDecimal UNLIMITED_BALANCE = new BigDecimal("999999999");

    private final SubscriptionRepository subscriptionRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final ModelPricingService pricingService;
    private final boolean unlimited;

    /**
     * True when this deployment runs with unlimited credits (CE default:
     * credit.unlimited=true). Gates that exist only to protect BILLING
     * (pre-flight cost checks, free-inference loopholes) are meaningless in
     * this mode and must not block execution.
     */
    public boolean isUnlimited() {
        return unlimited;
    }
    private final boolean markupEnabled;
    private final boolean markupShadow;
    private final BigDecimal webSearchCreditsPerSearch;
    private final BigDecimal webSearchCreditsPerFetch;

    /**
     * PR8 (Q1=b) - redirect TEAM-billable credit consumption to the org owner.
     * Optional injection so legacy 4-arg / 6-arg constructors in existing tests
     * continue to work; production wiring goes through the @Autowired ctor that
     * injects this. When null, falls back to executor-billing (legacy behaviour).
     */
    private com.apimarketplace.auth.service.PlanResolutionService planResolutionService;

    /**
     * PR11 - per-member quota cap enforcement. Optional bean (null in legacy
     * tests / CE mode where org-membership semantics don't apply). When non-null,
     * every TEAM-billable {@code consumeFor*} short-circuits before debit if
     * the executor has hit their cap, returning {@link CreditConsumeResult#quotaCapExceeded}.
     */
    private MemberQuotaService memberQuotaService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemberQuotaService(MemberQuotaService memberQuotaService) {
        this.memberQuotaService = memberQuotaService;
    }

    /**
     * Reward-code benefit (workflow-node executions free for redeemers of a
     * FREE_NODE_COUNTER program, time-boxed + per-account capped). Optional bean:
     * null in legacy unit-test ctors, and irrelevant in CE (unlimited mode never
     * engages the free-node path). When non-null AND credits are actually metered,
     * {@link #consumeForWorkflowNode} skips the debit for one node each time the
     * executor has a usable free-node slot. Setter (not constructor) injection
     * keeps the RewardService -> CreditService grant dependency acyclic.
     */
    private RewardService rewardService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRewardService(RewardService rewardService) {
        this.rewardService = rewardService;
    }

    /**
     * Legacy 4-arg ctor kept for existing test call-sites that pre-date the
     * markup feature flags. Defaults to {@code markupEnabled=true, shadow=false}
     * so those tests continue to exercise the production ledger-write path.
     * Spring always picks the annotated ctor thanks to {@code @Autowired} preference
     * on the widest matching constructor with injectable params.
     */
    public CreditService(SubscriptionRepository subscriptionRepository,
                         CreditLedgerRepository ledgerRepository,
                         ModelPricingService pricingService,
                         boolean unlimited) {
        this(subscriptionRepository, ledgerRepository, pricingService, unlimited, true, false, BigDecimal.ONE);
    }

    public CreditService(SubscriptionRepository subscriptionRepository,
                         CreditLedgerRepository ledgerRepository,
                         ModelPricingService pricingService,
                         boolean unlimited,
                         boolean markupEnabled,
                         boolean markupShadow) {
        this(subscriptionRepository, ledgerRepository, pricingService, unlimited,
                markupEnabled, markupShadow, BigDecimal.ONE);
    }

    /**
     * PR8 setter for optional Spring autowiring of the payer resolver +
     * kill-switch flag. Field-level @Autowired(required=false) so unit tests
     * constructing CreditService directly via the legacy ctors don't break.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlanResolutionService(com.apimarketplace.auth.service.PlanResolutionService planResolutionService) {
        this.planResolutionService = planResolutionService;
    }

    /**
     * Owner-pays - resolve the actual payer for a credit consumption.
     * Returns the org owner's userId when the executor is a MEMBER/ADMIN/VIEWER
     * of a non-personal default workspace; otherwise returns the executor as-is.
     *
     * <p>Falls back to executor on any of: kill-switch off, resolver bean
     * unwired (legacy test / CE mode), null result, or any internal exception.
     * Never throws - credit consumption stays green.
     *
     * <p>Marketplace + BYOK paths bypass this helper entirely (see allow-list
     * call sites that pass the executor userId directly into deductCredits).
     */
    /**
     * PR11 - short-circuit gate. Returns a {@link CreditConsumeResult#quotaCapExceeded}
     * when the executor would exceed their per-member credits cap; otherwise
     * returns null and the caller proceeds to the debit. Null-safe on every
     * input (no resolver wired = no enforcement = legacy behaviour preserved).
     *
     * <p>Called BEFORE {@code deductCredits} so a cap-exceeded consume never
     * touches the owner's wallet AND never lands a ledger row. The cap message
     * surfaces a different remediation than insufficient-credits ("ask your
     * admin to raise your cap" vs "top up the wallet").
     */
    private CreditConsumeResult enforceQuotaCap(Long executorUserId, BigDecimal additionalCost,
                                                 Long payerForBalanceReadback) {
        if (memberQuotaService == null) return null;
        MemberQuotaService.CapDecision decision =
                memberQuotaService.checkCreditsCap(executorUserId, additionalCost);
        if (decision.allowed()) return null;
        return CreditConsumeResult.quotaCapExceeded(
                decision.dimension(), decision.consumed(), decision.cap(),
                getBalance(payerForBalanceReadback));
    }

    /**
     * v9-revised - owner-pays is now the only routing. No flag, no fallback to
     * legacy self-billing. The resolver bean is still optional ({@code required=false})
     * so CE / unit tests constructing CreditService directly without
     * PlanResolutionService keep working - null resolver simply returns the
     * executor (= self-pay), which is the right answer for solo workspaces
     * anyway (the resolver would have returned the same).
     */
    private Long resolvePayer(Long executorUserId) {
        if (planResolutionService == null || executorUserId == null) {
            return executorUserId;
        }
        try {
            Long payer = planResolutionService.resolvePayerUserId(executorUserId);
            return payer != null ? payer : executorUserId;
        } catch (Exception e) {
            return executorUserId;
        }
    }

    /**
     * V366 (ADR-0010) - the active workspace this consume is happening in,
     * stamped onto each consumption ledger row as a descriptive reporting tag
     * so the Quota page can slice usage per workspace. Reads the request/async
     * org scope ({@code X-Organization-ID} header, or the ThreadLocal bound by
     * {@code TenantResolver.runWithOrgScope} on async consume paths - already
     * populated upstream by {@code CreditConsumptionClient} for the
     * dead-letter / observability org tag).
     *
     * <p>Returns {@code null} when there is no active workspace context (system
     * grants, background reapers, legacy callers). Such rows stay unattributed
     * and surface only under the "All workspaces" usage aggregate, never in a
     * per-workspace slice. This NEVER affects routing or balance - owner-pays
     * still keys the wallet on {@code user_id}.
     */
    private String currentLedgerOrgId() {
        String orgId = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        return (orgId != null && !orgId.isBlank()) ? orgId : null;
    }

    // ============================================================================
    // Free-plan workflow-credit scoping (Cloud only - CE runs in unlimited mode).
    // The monthly Free grant lands on the sub bucket (remaining_credits). On the
    // FREE plan that bucket may fund ONLY workflow-node orchestration; every other
    // consumption (chat, agent, classify/guardrail/browser, web search/fetch,
    // image generation, platform markup) must draw the PAYG top-up bucket instead.
    // Paid plans are unaffected (the sub bucket funds everything); CE is unaffected
    // because every consume / pre-flight path short-circuits on `unlimited` before
    // reaching this routing. See AGENTS.md "Regle architecturale CE / Cloud".
    // ============================================================================

    /** source_types the Free monthly (sub) bucket is allowed to fund. */
    private static final java.util.Set<String> WORKFLOW_SUB_ELIGIBLE_SOURCE_TYPES =
            java.util.Set.of("WORKFLOW_NODE", "WORKFLOW_NODE_PROMO");

    /**
     * Source types that land on the PAYG bucket (persist across renewal) rather
     * than the sub bucket (wiped on renewal). PAYG_TOPUP is the Stripe one-time
     * top-up; the referral reward and its clawback must BOTH route here, or a
     * clawback would debit the wrong bucket (user keeps the reward, loses unrelated
     * sub credits).
     */
    private static final java.util.Set<String> PAYG_BUCKET_SOURCE_TYPES =
            java.util.Set.of("PAYG_TOPUP", "REWARD_REFERRAL", "REWARD_CLAWBACK");

    /** Plan code whose monthly (sub) bucket is restricted to workflow orchestration. */
    private static final String WORKFLOW_CREDITS_ONLY_PLAN_CODE = "FREE";

    /**
     * True when the {@code sub} (monthly) bucket may fund a debit of
     * {@code sourceType}. Always true in unlimited mode (CE) and for non-FREE
     * plans, so this is a behavioural no-op everywhere except FREE-plan Cloud
     * accounts. On the FREE plan only workflow-node orchestration may draw the
     * monthly grant; every other source type is restricted to the PAYG bucket.
     */
    private boolean subBucketEligible(Subscription sub, String sourceType) {
        if (unlimited) return true;
        if (!isWorkflowCreditsOnlyPlan(sub)) return true;
        return sourceType != null && WORKFLOW_SUB_ELIGIBLE_SOURCE_TYPES.contains(sourceType);
    }

    /**
     * True when {@code sub} is on the FREE plan whose monthly bucket is
     * workflow-scoped. Both the bucket routing ({@link #subBucketEligible})
     * and the PAYG-debt leg of the delinquency lifecycle apply ONLY here:
     * on paid plans every debit nets against the two-bucket total, so a
     * positive total means any PAYG deficit is already economically
     * recovered and must not block the account.
     */
    private boolean isWorkflowCreditsOnlyPlan(Subscription sub) {
        if (unlimited || sub == null || sub.getPlan() == null) return false;
        return WORKFLOW_CREDITS_ONLY_PLAN_CODE.equalsIgnoreCase(sub.getPlan().getCode());
    }

    /**
     * Balance eligible to fund a debit of {@code sourceType}: the full two-bucket
     * total when {@link #subBucketEligible} holds, otherwise the PAYG bucket alone.
     */
    private BigDecimal eligibleBalance(Subscription sub, String sourceType) {
        return subBucketEligible(sub, sourceType)
                ? sub.getTotalBalance()
                : sub.getPaygRemainingCredits();
    }

    private static BigDecimal normalizeWebSearchCreditsPerSearch(BigDecimal configuredCost) {
        BigDecimal cost = configuredCost != null ? configuredCost : BigDecimal.ONE;
        if (cost.signum() < 0) {
            throw new IllegalArgumentException("billing.websearch.credits-per-search must be >= 0");
        }
        return cost;
    }

    private static BigDecimal normalizeWebFetchCreditsPerFetch(BigDecimal configuredCost) {
        BigDecimal cost = configuredCost != null ? configuredCost : BigDecimal.ONE;
        if (cost.signum() < 0) {
            throw new IllegalArgumentException("billing.websearch.credits-per-fetch must be >= 0");
        }
        return cost;
    }

    /**
     * Legacy 7-arg ctor kept for existing test call-sites that pre-date the
     * separate web-fetch billing price. Defaults the per-fetch cost to 1 credit.
     */
    public CreditService(SubscriptionRepository subscriptionRepository,
                         CreditLedgerRepository ledgerRepository,
                         ModelPricingService pricingService,
                         boolean unlimited,
                         boolean markupEnabled,
                         boolean markupShadow,
                         BigDecimal webSearchCreditsPerSearch) {
        this(subscriptionRepository, ledgerRepository, pricingService, unlimited,
                markupEnabled, markupShadow, webSearchCreditsPerSearch, BigDecimal.ONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CreditService(SubscriptionRepository subscriptionRepository,
                         CreditLedgerRepository ledgerRepository,
                         ModelPricingService pricingService,
                         // Economic kill-switches/levers - defaults declared in application.yml;
                         // PROD-effective values pinned in values-prod.yaml (services.auth.env).
                         // credit.unlimited MUST stay false in cloud prod (true = consumption
                         // tracked but balance infinite = NO billing). See the project docs.
                         @Value("${credit.unlimited:false}") boolean unlimited,
                         @Value("${credentials.platform.markup.enabled:true}") boolean markupEnabled,
                         @Value("${credentials.platform.markup.shadow:false}") boolean markupShadow,
                         @Value("${billing.websearch.credits-per-search:1}") BigDecimal webSearchCreditsPerSearch,
                         @Value("${billing.websearch.credits-per-fetch:1}") BigDecimal webSearchCreditsPerFetch) {
        this.subscriptionRepository = subscriptionRepository;
        this.ledgerRepository = ledgerRepository;
        this.pricingService = pricingService;
        this.unlimited = unlimited;
        this.markupEnabled = markupEnabled;
        this.markupShadow = markupShadow;
        this.webSearchCreditsPerSearch = normalizeWebSearchCreditsPerSearch(webSearchCreditsPerSearch);
        this.webSearchCreditsPerFetch = normalizeWebFetchCreditsPerFetch(webSearchCreditsPerFetch);
        log.info("CreditService: web search billing cost set to {} credit(s) per search", this.webSearchCreditsPerSearch);
        log.info("CreditService: web fetch billing cost set to {} credit(s) per fetch", this.webSearchCreditsPerFetch);
        if (unlimited) {
            log.info("CreditService: unlimited mode enabled - consumption tracked but balance is infinite");
        }
        if (!markupEnabled) {
            log.info("CreditService: platform markup billing is DISABLED - no ledger rows will be written for markup debits");
        } else if (markupShadow) {
            log.info("CreditService: platform markup is in SHADOW mode - debits are logged for observability but no ledger rows are written");
        }
    }

    @Transactional
    public CreditConsumeResult consumeForAgent(Long userId, String sourceId,
                                                String provider, String model,
                                                int promptTokens, int completionTokens) {
        return consumeForAgent(userId, sourceId, provider, model, promptTokens, completionTokens, "AGENT_EXECUTION");
    }

    @Transactional
    public CreditConsumeResult consumeForAgent(Long userId, String sourceId,
                                                String provider, String model,
                                                int promptTokens, int completionTokens,
                                                String sourceType) {
        return consumeForAgent(userId, sourceId, provider, model,
                LlmTokenBreakdown.of(promptTokens, completionTokens), sourceType);
    }

    /**
     * Cache-aware variant: the breakdown carries cache write/read, cached-subset and
     * reasoning counters so {@link ModelPricingService#calculateCost(String, String,
     * LlmTokenBreakdown)} can bill each token class at the provider's true relative
     * price instead of full input rate. Ledger prompt/completion columns keep the raw
     * provider-reported counts.
     */
    @Transactional
    public CreditConsumeResult consumeForAgent(Long userId, String sourceId,
                                                String provider, String model,
                                                LlmTokenBreakdown usage,
                                                String sourceType) {
        BigDecimal cost = pricingService.calculateCost(provider, model, usage);
        Long debitUserId = resolvePayer(userId);
        String desc = appendExecutorAudit(
                String.format("%s/%s: %d input + %d output tokens%s", provider, model,
                        usage.promptTokens(), usage.completionTokens(), cacheAuditSuffix(usage)),
                userId, debitUserId);
        return deductCredits(debitUserId, cost, sourceType, sourceId, provider, model,
                usage.promptTokens(), usage.completionTokens(),
                Math.max(usage.cachedTokens(), usage.cacheReadTokens()),
                desc, /*allowNegative*/ false, /*executorUserId*/ userId);
    }

    /**
     * Audit suffix describing the cache/reasoning composition of a turn - kept in the
     * ledger description so a billed amount lower than {@code rate*prompt} is
     * self-explanatory when reading the row.
     */
    private static String cacheAuditSuffix(LlmTokenBreakdown usage) {
        StringBuilder sb = new StringBuilder();
        if (usage.cacheCreationTokens() > 0) sb.append(", cache write ").append(usage.cacheCreationTokens());
        if (usage.cacheReadTokens() > 0) sb.append(", cache read ").append(usage.cacheReadTokens());
        if (usage.cachedTokens() > 0) sb.append(", cached ").append(usage.cachedTokens());
        if (usage.reasoningTokens() > 0) sb.append(", reasoning ").append(usage.reasoningTokens());
        return sb.toString();
    }

    @Transactional
    public CreditConsumeResult consumeForWorkflowNode(Long userId, String sourceId) {
        Long debitUserId = resolvePayer(userId);
        // Idempotency: interface/signal nodes may fire multiple actions at the same epoch/spawn,
        // each triggering consumeCreditForSignalNode with the same sourceId. Skip if already charged.
        // Check against PAYER (where the ledger will live) - not executor.
        CreditConsumeResult dup = checkIdempotency(debitUserId, sourceId);
        if (dup != null) return dup;
        // Launch promo: when credits are actually metered (Cloud) and the EXECUTOR
        // holds a usable free-node benefit, atomically claim one slot and record a
        // 0-cost audit row instead of debiting. Gated on !unlimited so CE keeps its
        // existing tracking row and never burns the cap. The claim joins THIS
        // transaction, so it rolls back with the ledger row; the source_id unique
        // index keeps a node-retry idempotent (it short-circuits above).
        if (!unlimited && rewardService != null && rewardService.claimFreeWorkflowNode(userId)) {
            return recordPromoFreeWorkflowNode(debitUserId, userId, sourceId);
        }
        BigDecimal cost = BigDecimal.ONE; // 1 credit per node in both CE and Cloud - CE tracks for observability
        // PR11d-a: cap enforcement now inside deductCredits.
        String desc = appendExecutorAudit("Workflow node: " + sourceId, userId, debitUserId);
        return deductCredits(debitUserId, cost, "WORKFLOW_NODE", sourceId, null, null, null, null, desc, userId);
    }

    /**
     * Write a 0-cost {@code WORKFLOW_NODE_PROMO} ledger row for a node made free by
     * an active launch-promo benefit. Mirrors the executor/payer attribution of a
     * normal debit ({@code userId} = payer, {@code executorUserId} = executor) so
     * analytics and the {@code source_id} idempotency guard behave identically -
     * only the balance is left untouched.
     */
    private CreditConsumeResult recordPromoFreeWorkflowNode(Long payerUserId, Long executorUserId, String sourceId) {
        BigDecimal balance = unlimited ? UNLIMITED_BALANCE : getBalance(payerUserId);
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(payerUserId);
        entry.setExecutorUserId(executorUserId);  // PR11 - quota enforcement key
        entry.setOrganizationId(currentLedgerOrgId());  // V366 - workspace reporting tag
        entry.setAmount(BigDecimal.ZERO);
        entry.setBalanceAfter(balance);
        entry.setSourceType("WORKFLOW_NODE_PROMO");
        entry.setSourceId(sourceId);
        entry.setDescription(truncateDescription(
                appendExecutorAudit("Workflow node (promo, free): " + sourceId, executorUserId, payerUserId)));
        ledgerRepository.save(entry);
        log.info("Workflow node free via promo: executor={} payer={} source={}", executorUserId, payerUserId, sourceId);
        return CreditConsumeResult.success(BigDecimal.ZERO, balance);
    }

    /**
     * Marketplace purchase. Bypasses the owner-pays redirect so the purchasing
     * user's wallet is debited regardless of which workspace they're in (the
     * acquired publication is owned by the purchaser, not the workspace).
     */
    @Transactional
    public CreditConsumeResult consumeForMarketplacePurchase(Long userId, String publicationId, int credits) {
        BigDecimal cost = BigDecimal.valueOf(credits);
        return deductCredits(userId, cost, "MARKETPLACE_PURCHASE", publicationId,
                null, null, null, null, "Publication purchase: " + publicationId, userId);
    }

    /**
     * Debit credits for one web-search tool call. Cost is a fixed backend
     * setting ({@code billing.websearch.credits-per-search}, default 1), just
     * like workflow-node billing uses a fixed platform fee.
     *
     * <p>Idempotent via {@code sourceId} - typically built by
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#webSearchDebitChat}
     * (chat scope) or
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#webSearchDebitWorkflow}
     * (workflow scope).
     */
    @Transactional
    public CreditConsumeResult consumeForWebSearch(Long userId, String sourceId) {
        Long debitUserId = resolvePayer(userId);
        CreditConsumeResult dup = checkIdempotency(debitUserId, sourceId);
        if (dup != null) return dup;
        String desc = appendExecutorAudit("Web search: " + sourceId, userId, debitUserId);
        return deductCredits(debitUserId, webSearchCreditsPerSearch, "WEB_SEARCH", sourceId,
                "websearch", "default", null, null, desc, userId);
    }

    /**
     * Debit credits for one web-fetch (page extraction) tool call. Cost is a
     * fixed backend setting ({@code billing.websearch.credits-per-fetch},
     * default 1), tracked under a dedicated {@code WEB_FETCH} source type so it
     * surfaces and filters separately from {@code WEB_SEARCH} on the quota /
     * usage analytics page.
     *
     * <p>Idempotent via {@code sourceId} - typically built by
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#webFetchDebitChat}
     * (chat scope) or
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#webFetchDebitWorkflow}
     * (workflow scope).
     */
    @Transactional
    public CreditConsumeResult consumeForWebFetch(Long userId, String sourceId) {
        Long debitUserId = resolvePayer(userId);
        CreditConsumeResult dup = checkIdempotency(debitUserId, sourceId);
        if (dup != null) return dup;
        String desc = appendExecutorAudit("Web fetch: " + sourceId, userId, debitUserId);
        return deductCredits(debitUserId, webSearchCreditsPerFetch, "WEB_FETCH", sourceId,
                "websearch", "default", null, null, desc, userId);
    }

    /**
     * Debit credits for one image-generation tool call. Cost =
     * {@link ModelPricingService#calculateUnitCost}{@code (provider, model, actualImageCount)}
     * which returns {@code input_rate × n + fixed_cost} (no /1000 divisor).
     *
     * <p><b>{@code actualImageCount} contract</b> &mdash; callers MUST pass
     * the count of images <i>actually returned</i> by the provider, not the
     * count requested. {@code gpt-image-1} can return fewer images than
     * requested when per-image content moderation rejects a subset; billing
     * the requested count would over-charge the user.
     *
     * <p><b>Pseudo-model billing key</b> &mdash; {@code model} here is the
     * billing pseudo-model (e.g. {@code gpt-image-1-low}), not the real
     * provider model name. See
     * {@code shared-agent-lib/.../imagegen/ImageProviderCatalog} for the
     * mapping from provider quality flags to billing keys.
     *
     * <p>Pre-flight {@link ModelPricingService#hasPricing} guard prevents the
     * default-rate fallback from masking a missing-pricing deployment bug.
     * Idempotent via {@code sourceId} from
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#imageGenerationDebitChat}
     * or
     * {@link com.apimarketplace.common.credit.SourceIdBuilder#imageGenerationDebitWorkflow}.
     */
    @Transactional
    public CreditConsumeResult consumeForImageGeneration(Long userId, String sourceId,
                                                          String provider, String model,
                                                          int actualImageCount) {
        if (actualImageCount <= 0) {
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        if (!pricingService.hasPricing(provider, model)) {
            log.warn("No pricing row for ({}, {}) - refusing image-generation debit. Check V141 / ImageProviderCatalog.",
                    provider, model);
            return CreditConsumeResult.noPricing(provider, model);
        }
        Long debitUserId = resolvePayer(userId);
        CreditConsumeResult dup = checkIdempotency(debitUserId, sourceId);
        if (dup != null) return dup;
        BigDecimal cost = pricingService.calculateUnitCost(provider, model, actualImageCount);
        String desc = appendExecutorAudit(
                String.format("Image generation %s/%s: %d image(s)", provider, model, actualImageCount),
                userId, debitUserId);
        return deductCredits(debitUserId, cost, "IMAGE_GENERATION", sourceId,
                provider, model, actualImageCount, null, desc, userId);
    }

    /**
     * BYOK trace for image generation - the user supplied their own upstream
     * API key, so the platform does NOT deduct credits (the user pays the
     * provider directly). We still write a 0-amount ledger row to keep the
     * audit trail complete: who generated how many images via which model,
     * with which credential source.
     *
     * <p>BYOK does NOT route through {@code resolvePayer}: the workspace owner
     * has nothing to do with a member's own-key generation, so the trace row
     * stays attributed to the executor on every column ({@code user_id ==
     * executor_user_id}) and {@code balance_after} reads the executor's own
     * wallet. Keeps the {@code user_id} ↔ {@code balance_after} invariant
     * intact even though no credits are debited.
     *
     * <p>Bypasses {@link #deductCredits} (which has no zero-amount-audit
     * branch when {@code cost==0}) and writes the ledger row directly.
     * Idempotent via {@code sourceId}; zero-image responses skip even the
     * trace (consistent with {@link #consumeForImageGeneration}).
     *
     * <p>Used by {@code CatalogBillingDispatcher} when the catalog runtime
     * resolves a {@code USER} credential source for a billable tool call.
     */
    @Transactional
    public CreditConsumeResult consumeForImageGenerationByok(Long userId, String sourceId,
                                                              String provider, String model,
                                                              int actualImageCount) {
        if (actualImageCount <= 0) {
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalanceForSelf(userId));
        }
        if (sourceId != null && ledgerRepository.existsBySourceId(sourceId)) {
            log.debug("BYOK trace already recorded for sourceId={}, skipping duplicate", sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalanceForSelf(userId));
        }

        BigDecimal balance = getBalanceForSelf(userId);
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(userId);
        entry.setExecutorUserId(userId);
        entry.setOrganizationId(currentLedgerOrgId());  // V366 - workspace reporting tag
        entry.setAmount(BigDecimal.ZERO);
        entry.setBalanceAfter(balance);
        entry.setSourceType("IMAGE_GENERATION_BYOK");
        entry.setSourceId(sourceId);
        entry.setProvider(provider);
        entry.setModel(model);
        entry.setPromptTokens(actualImageCount);
        entry.setCompletionTokens(null);
        entry.setDescription(String.format(
                "Image generation %s/%s: %d image(s) [BYOK - user-key passthrough]",
                provider, model, actualImageCount));
        ledgerRepository.save(entry);
        log.debug("BYOK trace recorded for user {} {}/{} ({} images, sourceId={})",
                userId, provider, model, actualImageCount, sourceId);
        return CreditConsumeResult.success(BigDecimal.ZERO, balance);
    }

    /**
     * Read the user's own wallet balance without owner-pays redirection.
     * BYOK is the only caller - every other balance read goes through
     * {@link #getBalance} which routes via {@link #resolvePayer}.
     *
     * <p>V250: returns total balance (sub + payg) - symmetric with
     * {@link #getBalance}. A BYOK user with PAYG-only balance now sees their
     * actual spending authority instead of just the sub bucket.
     */
    private BigDecimal getBalanceForSelf(Long userId) {
        if (unlimited) return UNLIMITED_BALANCE;
        if (userId == null) return BigDecimal.ZERO;
        return subscriptionRepository.findActiveByUserId(userId)
                .map(Subscription::getTotalBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Idempotency short-circuit shared by all flat-cost {@code consumeFor*}
     * wrappers. Returns a no-op {@link CreditConsumeResult#success} when a
     * ledger row already exists for {@code sourceId}, or {@code null} when
     * the caller should proceed to the deduction path.
     *
     * <p>Note: this is a read-then-write pattern. Concurrent callers can both
     * miss the {@code existsBySourceId} check; the unique constraint on
     * {@code source_id} in {@code credit_ledger} is the actual race-safe
     * guarantee. This helper is a fast path that avoids the SELECT FOR UPDATE
     * on the subscription row when the ledger row already exists.
     */
    private CreditConsumeResult checkIdempotency(Long userId, String sourceId) {
        if (sourceId != null && ledgerRepository.existsBySourceId(sourceId)) {
            log.debug("Credit already recorded for sourceId={}, skipping duplicate", sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        return null;
    }

    /**
     * Debit credits for one chat turn. Cost = {@code pricingService.calculateCost(provider,
     * model, promptTokens, completionTokens)} read verbatim from {@code auth.model_pricing} -
     * there is <b>no</b> {@code provider_kind='bridge'} short-circuit. Since V130, bridge rows
     * carry the underlying cloud model's list price (Product 2026-04), so a bridge turn
     * debits credits at the same per-token rate as the cloud API route. The admin pays for
     * the CLI subscription externally; tenants still burn ledger credits so reporting /
     * future markup rules stay comparable across routes.
     *
     * <p>V130's Flyway header still mentions a V117 "bridge short-circuit" - that comment
     * describes a superseded design and is not enforced by this service. The current
     * behaviour is: same provider/model key → same per-token cost, regardless of bridge kind.
     */
    @Transactional
    public CreditConsumeResult consumeForChat(Long userId, String conversationId,
                                               String provider, String model,
                                               int promptTokens, int completionTokens) {
        return consumeForChat(userId, conversationId, provider, model,
                LlmTokenBreakdown.of(promptTokens, completionTokens));
    }

    /** Cache-aware variant - see {@link #consumeForAgent(Long, String, String, String, LlmTokenBreakdown, String)}. */
    @Transactional
    public CreditConsumeResult consumeForChat(Long userId, String conversationId,
                                               String provider, String model,
                                               LlmTokenBreakdown usage) {
        Long debitUserId = resolvePayer(userId);
        // Idempotency narrowed to CHAT_CONVERSATION - see method Javadoc.
        if (conversationId != null
                && ledgerRepository.findFirstBySourceIdAndSourceType(conversationId, "CHAT_CONVERSATION").isPresent()) {
            log.debug("Chat already debited for conversationId={}, skipping duplicate", conversationId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(debitUserId));
        }
        BigDecimal cost = pricingService.calculateCost(provider, model, usage);
        String desc = appendExecutorAudit(
                String.format("Chat %s/%s: %d input + %d output tokens%s",
                        provider, model, usage.promptTokens(), usage.completionTokens(), cacheAuditSuffix(usage)),
                userId, debitUserId);
        return deductCredits(debitUserId, cost, "CHAT_CONVERSATION", conversationId, provider, model,
                usage.promptTokens(), usage.completionTokens(),
                Math.max(usage.cachedTokens(), usage.cacheReadTokens()),
                desc, /*allowNegative*/ true, userId);
    }

    /**
     * Cache-aware, idempotent post-flight debit for a CE LLM relay execution.
     *
     * <p>Two CE relay billing models share the {@code CE_LLM_RELAY} sourceType and both
     * route here:
     * <ul>
     *   <li><b>Legacy per-call</b> - one ledger row per forwarded completion, keyed by a
     *       unique {@code "ce-llm-"+UUID} sourceId. The idempotency guard below never
     *       matches (each sourceId is fresh), so behaviour is one row per call as before.</li>
     *   <li><b>Centralized per-execution</b> - ONE row per CE execution, keyed by the stable
     *       {@code executionId}. The relay's end-of-execution settle AND the crash-recovery
     *       reaper may both call this with the same {@code executionId}; the guard makes the
     *       second a silent no-op so they can never double-bill.</li>
     * </ul>
     *
     * <p>The application-level idempotency CHECK is narrowed to {@code (sourceId, CE_LLM_RELAY)}
     * - same shape as {@link #consumeForChat} - so a {@code *_REJECTED} audit row never short-circuits
     * a real debit. The hard backstop underneath is the GLOBAL unique index on {@code source_id}
     * ({@code idx_cl_source_id_unique}): combined with the {@code PESSIMISTIC_WRITE} lock taken in
     * {@code deductCredits}, a settle racing the reaper (or a retry) on the same {@code executionId}
     * can never write two ledger rows - the loser sees the committed row and no-ops.
     *
     * <p>{@code allowNegative=true}: the relayed LLM call(s) already ran on the CE runtime,
     * so the incurred cost must be recorded even if it drives the wallet negative
     * (delinquency) - never dropped. This matches {@link #consumeForChat}; the per-call
     * {@code checkChatBudget} gate bounds mid-execution over-spend up-front.
     */
    @Transactional
    public CreditConsumeResult consumeForCeRelay(Long userId, String sourceId,
                                                 String provider, String model,
                                                 LlmTokenBreakdown usage) {
        Long debitUserId = resolvePayer(userId);
        // Idempotency narrowed to CE_LLM_RELAY - see method Javadoc. Guards the
        // centralized settle/reaper race; a no-op for the unique-per-call legacy path.
        if (sourceId != null
                && ledgerRepository.findFirstBySourceIdAndSourceType(sourceId, "CE_LLM_RELAY").isPresent()) {
            log.debug("CE relay already settled for sourceId={}, skipping duplicate", sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(debitUserId));
        }
        BigDecimal cost = pricingService.calculateCost(provider, model, usage);
        String desc = appendExecutorAudit(
                String.format("CE relay %s/%s: %d input + %d output tokens%s",
                        provider, model, usage.promptTokens(), usage.completionTokens(), cacheAuditSuffix(usage)),
                userId, debitUserId);
        return deductCredits(debitUserId, cost, "CE_LLM_RELAY", sourceId, provider, model,
                usage.promptTokens(), usage.completionTokens(),
                Math.max(usage.cachedTokens(), usage.cacheReadTokens()),
                desc, /*allowNegative*/ true, userId);
    }

    /**
     * Debit platform-credential markup for a single api-tool call.
     *
     * <p>Idempotent via the {@code sourceId} unique index - a duplicate call
     * for the same {@code sourceId} is a silent no-op. Zero-or-negative markup
     * bypasses the ledger entirely. Insufficient balance returns a failure
     * result (never throws) so the caller can decide to abort the run vs.
     * degrade to shadow billing.
     *
     * @param sourceId    idempotency key, typically
     *                    {@code platform-markup:RUN:<runId>:step:<stepId>:<nonce>}
     * @param apiToolName human-readable name for description field
     * @param markupAmount per-call markup credits (>= 0)
     * @param runId       workflow run identifier (for description/audit only)
     */
    @Transactional
    public CreditConsumeResult consumePlatformMarkup(Long userId,
                                                      String sourceId,
                                                      String apiToolName,
                                                      BigDecimal markupAmount,
                                                      String runId) {
        if (markupAmount == null || markupAmount.signum() <= 0) {
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        // Feature-gate: when markup is disabled or in shadow mode, return success
        // WITHOUT writing the ledger row. Shadow mode logs the would-be debit so
        // ops can audit projected spend before the real rollout. Dark-launching a
        // billing flow is only safe if the flag actually gates the DB write.
        if (!markupEnabled) {
            log.debug("Markup disabled - would-be debit skipped: user={}, tool={}, amount={}, sourceId={}",
                    userId, apiToolName, markupAmount, sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        if (markupShadow) {
            log.info("[SHADOW] Platform markup projection (not persisted): user={}, tool={}, amount={}, runId={}, sourceId={}",
                    userId, apiToolName, markupAmount, runId, sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        if (sourceId != null && ledgerRepository.existsBySourceId(sourceId)) {
            log.debug("Platform markup already recorded for sourceId={}, skipping duplicate", sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }
        // PR8 (Q1=b): resolve org-owner-as-payer for platform markup too.
        // PR11d-a: cap enforcement now inside deductCredits.
        Long payerUserId = resolvePayer(userId);
        String description = appendExecutorAudit(
                "Platform markup: " + (apiToolName == null ? "<unknown>" : apiToolName)
                        + (runId == null ? "" : " [run=" + runId + "]"),
                userId, payerUserId);
        return deductCredits(payerUserId, markupAmount, "PLATFORM_MARKUP", sourceId,
                null, null, null, null, description, userId);
    }

    @Transactional
    public CreditConsumeResult grantCredits(Long userId, BigDecimal amount, String sourceType, String sourceId, String description) {
        if (unlimited) {
            log.debug("Grant {} credits to user {} ignored [unlimited mode]", amount, userId);
            return CreditConsumeResult.success(BigDecimal.ZERO, UNLIMITED_BALANCE);
        }

        Subscription sub = findSubscriptionForUpdate(userId);
        if (sub == null) {
            return CreditConsumeResult.noSubscription();
        }

        // V250 routing: PAYG one-time top-up lands on the payg bucket,
        // everything else (subscription renewal, admin grant, marketplace
        // refund) lands on the sub bucket. The bucket assignment dictates
        // sub-renewal persistence: sub-bucket grants get wiped on renewal,
        // payg grants persist across cycles.
        boolean isPaygBucket = PAYG_BUCKET_SOURCE_TYPES.contains(sourceType);
        if (isPaygBucket) {
            sub.setPaygRemainingCredits(sub.getPaygRemainingCredits().add(amount));
        } else {
            sub.setRemainingCredits(sub.getRemainingCredits().add(amount));
        }
        clearDelinquentIfPositive(sub);
        subscriptionRepository.save(sub);

        BigDecimal newTotal = sub.getTotalBalance();
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(userId);
        entry.setExecutorUserId(userId);  // PR11 - grant: executor == recipient
        entry.setAmount(amount);
        entry.setBalanceAfter(newTotal);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setDescription(truncateDescription(description));
        ledgerRepository.save(entry);

        log.info("Granted {} credits to user {} ({} -> {} bucket). New total balance: {}",
                amount, userId, sourceType, isPaygBucket ? "payg" : "sub", newTotal);
        return CreditConsumeResult.success(BigDecimal.ZERO, newTotal);
    }

    /**
     * Returns the active wallet balance for the user. Under owner-pays, a
     * guest's wallet IS the workspace owner's wallet - so this method routes
     * the input userId through {@link #resolvePayer} before looking up the
     * subscription. Without this, a guest user calling {@code GET /balance}
     * would see {@code 0} (no personal sub) while their consumes succeed
     * against the owner's wallet - UI shows wrong number, gates fire
     * incorrectly.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long userId) {
        if (unlimited) return UNLIMITED_BALANCE;
        Long payerUserId = resolvePayer(userId);
        Subscription sub = resolveActiveSubscription(payerUserId);
        if (sub == null) return BigDecimal.ZERO;
        // V250: return sum of sub + payg buckets (Subscription.getTotalBalance).
        // Pre-V250 callers expecting "remaining_credits only" should be audited
        // and migrated - getBalance is the canonical wallet-status read.
        return sub.getTotalBalance();
    }

    /**
     * V250 - bucket-aware balance view for the wallet UI. Returns the two
     * buckets separately so the frontend can render "sub: X / payg: Y"
     * without doing arithmetic. Sums match {@link #getBalance(Long)}.
     *
     * <p>CE (unlimited mode) returns the sentinel on both buckets so the
     * frontend's "infinity" rendering does not need a special case.
     */
    @Transactional(readOnly = true)
    public BalanceBreakdown getBalanceBreakdown(Long userId) {
        if (unlimited) {
            return new BalanceBreakdown(UNLIMITED_BALANCE, UNLIMITED_BALANCE, UNLIMITED_BALANCE, false);
        }
        Long payerUserId = resolvePayer(userId);
        Subscription sub = resolveActiveSubscription(payerUserId);
        if (sub == null) {
            return new BalanceBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal subBal = sub.getRemainingCredits();
        BigDecimal paygBal = sub.getPaygRemainingCredits();
        return new BalanceBreakdown(subBal.add(paygBal), subBal, paygBal, Boolean.TRUE.equals(sub.getDelinquent()));
    }

    /**
     * V250 - DTO returned by {@link #getBalanceBreakdown(Long)}.
     * {@code balance} is always {@code subBalance + paygBalance} - kept on the
     * DTO so callers don't recompute.
     */
    public record BalanceBreakdown(
            BigDecimal balance,
            BigDecimal subBalance,
            BigDecimal paygBalance,
            boolean delinquent) {}

    /**
     * Resolve the user's most-recent active subscription. Owner-pays-aware:
     * the input userId is routed through {@link #resolvePayer} so callers can
     * pass the executor and get the payer's sub back. Internal callers in the
     * consume path already pre-resolved the payer; passing the payer through
     * is a no-op (executor == payer → same sub).
     */
    Subscription resolveActiveSubscription(Long userId) {
        if (userId == null) return null;
        Long payerUserId = resolvePayer(userId);
        return subscriptionRepository.findActiveByUserId(payerUserId).orElse(null);
    }

    /**
     * V148+: read-only access to the {@code subscription.delinquent} flag.
     * Surfaced via {@code GET /api/credits/balance} so the frontend can render
     * a "top up to resume" banner. False when no active subscription / unlimited.
     *
     * <p>Owner-pays: a guest's delinquency mirrors their workspace owner's
     * delinquency (same wallet, same delinquency state). Resolves the payer
     * before the lookup.
     */
    @Transactional(readOnly = true)
    public boolean isDelinquent(Long userId) {
        if (unlimited) return false;
        Long payerUserId = resolvePayer(userId);
        Subscription sub = resolveActiveSubscription(payerUserId);
        if (sub == null) return false;
        return Boolean.TRUE.equals(sub.getDelinquent());
    }

    /**
     * Owner-pays: the executor's pre-flight "do I have credits?" question
     * resolves against the PAYER's wallet - the only one their consumes will
     * ever debit. Legacy total-balance form - callers gating a spend whose
     * source type is known (chat, agent) should use the source-type-aware
     * overload so the FREE-plan bucket scoping applies.
     */
    @Transactional(readOnly = true)
    public boolean hasSufficientCredits(Long userId) {
        return hasSufficientCredits(userId, null);
    }

    /**
     * Source-type-aware existence check for the generic {@code /check} gate.
     * On the FREE plan (Cloud), a non-workflow {@code sourceType} is checked
     * against the PAYG bucket alone, mirroring the {@link #canAfford} pre-flight
     * and the post-flight {@link #deductCredits} bucket routing. Pre-fix, the
     * internal/scheduled chat gate called the total-balance form: a Free user
     * holding monthly workflow-only credits but no PAYG top-up passed the gate,
     * the LLM ran, and the post-flight debit drove the PAYG bucket negative.
     * A {@code null} sourceType keeps the legacy total-balance check (workflow
     * launch gates, where the Free monthly bucket IS eligible). Unlimited (CE)
     * and paid plans are unaffected (eligible balance == total balance).
     */
    @Transactional(readOnly = true)
    public boolean hasSufficientCredits(Long userId, String sourceType) {
        if (unlimited) return true;
        Subscription sub = resolveActiveSubscription(userId);
        if (sub == null) return false;
        BigDecimal available = sourceType == null
                ? sub.getTotalBalance()
                : eligibleBalance(sub, sourceType);
        return available.compareTo(BigDecimal.ONE) >= 0; // minimum 1 credit needed
    }

    /**
     * Non-committing budget check: does the user have at least {@code projectedCost}
     * credits available? Used by the chat pre-flight to refuse a turn that would
     * fail with 402 on post-flight {@code consumeForChat}, which would otherwise
     * let the LLM run and leave the ledger un-debited.
     *
     * <p>Owner-pays: routes through {@link #getBalance} which resolves the
     * payer - so a guest's pre-flight is gated by the OWNER's balance, the
     * one their {@code consumeForChat} would actually debit.
     */
    @Transactional(readOnly = true)
    public boolean canAfford(Long userId, BigDecimal projectedCost) {
        return canAfford(userId, projectedCost, null);
    }

    /**
     * Source-type-aware affordability for the chat / agent pre-flight gate. On
     * the FREE plan (Cloud), a non-workflow {@code sourceType} is checked against
     * the PAYG bucket alone, mirroring the post-flight {@link #deductCredits}
     * bucket routing: a Free user holding monthly workflow credits but no PAYG
     * top-up is refused a chat / agent turn up-front instead of letting the LLM
     * run and 402-ing post-flight. A {@code null} sourceType keeps the legacy
     * total-balance check (used by the back-compat two-arg overload). Unlimited
     * (CE) and paid plans are unaffected (eligible balance == total balance).
     */
    @Transactional(readOnly = true)
    public boolean canAfford(Long userId, BigDecimal projectedCost, String sourceType) {
        if (unlimited) return true;
        Subscription sub = resolveActiveSubscription(userId);
        if (sub == null) return false;
        BigDecimal available = sourceType == null
                ? sub.getTotalBalance()
                : eligibleBalance(sub, sourceType);
        if (projectedCost == null || projectedCost.signum() <= 0) {
            return available.compareTo(BigDecimal.ONE) >= 0; // minimum 1 credit needed
        }
        return available.compareTo(projectedCost) >= 0;
    }

    /**
     * Participation history feed. The quota page is workspace/wallet scoped:
     * summary, analytics, balance and history must resolve against the same
     * active workspace payer. In an org workspace that means the owner wallet,
     * not only rows where the requesting member was executor.
     *
     * <p>2026-05-21 user-reported fix - pre-fix this method called
     * {@code findByUserIdOrderByCreatedAtDesc(resolvePayer(userId), ...)} which
     * matched only rows where the user is the bill PAYER. A member of a TEAM
     * org whose actions are billed to the owner (rows with
     * {@code user_id=owner, executor_user_id=member}) saw nothing for
     * executions they performed - only their own personal-sub history before
     * joining the org. The participation finder
     * {@link CreditLedgerRepository#findByUserParticipationOrderByCreatedAtDesc}
     * matches {@code user_id = X OR executor_user_id = X}, so the user sees
     * everything they participated in regardless of who paid. We pass the
     * RAW userId (not {@code resolvePayer}) so a member of org A doesn't
     * accidentally see a different member's executions billed to the same
     * owner - strict per-user scope on the executor side.
     */
    @Transactional(readOnly = true)
    public Page<CreditLedgerEntry> getUsageHistory(Long userId, Pageable pageable) {
        return getUsageHistory(userId, null, pageable);
    }

    /**
     * V366 (ADR-0010) - workspace-scoped history feed. When {@code orgId} is
     * non-null, the feed is restricted to rows tagged with that workspace (the
     * per-workspace slice the Quota page shows when a single workspace is
     * picked). When {@code orgId} is null, returns the full participation
     * aggregate across ALL workspaces (the "All workspaces" view + every legacy
     * caller, unchanged) - including unattributed (NULL-org) historical rows.
     *
     * <p>Routing is untouched: {@code resolvePayer} still selects OWNER vs
     * MEMBER scope; the org tag is an additional, descriptive filter only.
     */
    @Transactional(readOnly = true)
    public Page<CreditLedgerEntry> getUsageHistory(Long userId, String orgId, Pageable pageable) {
        // 2026-05-22 user-reported fix - workspace-aware scope.
        // resolvePayer is workspace-context-aware (reads X-Active-Organization-ID
        // via PlanResolutionService.resolveCurrentWorkspaceMembership), so it
        // returns the OWNER of the CURRENT workspace.
        //
        //   - payer == self → user IS the workspace owner (personal workspace
        //     OR they own this team). Show ALL rows on the wallet, optionally
        //     narrowed to the picked workspace via orgId.
        //   - payer != self → user is a MEMBER of a TEAM workspace. Show only
        //     rows where they were the executor on THIS workspace's wallet.
        Long payerUserId = resolvePayer(userId);
        log.info("[getUsageHistory] viewer={} payer={} requestOrgId={} orgFilter={} branch={}",
                userId, payerUserId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId(),
                orgId,
                (payerUserId == null || payerUserId.equals(userId)) ? "OWNER_VIEW" : "MEMBER_VIEW");
        if (payerUserId == null || payerUserId.equals(userId)) {
            return ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, orgId, pageable);
        }
        return ledgerRepository.findByPayerAndExecutorOrderByCreatedAtDesc(payerUserId, userId, orgId, pageable);
    }

    public Page<CreditLedgerEntry> getUsageHistoryByType(Long userId, String sourceType, Pageable pageable) {
        return getUsageHistoryByType(userId, sourceType, null, pageable);
    }

    /** V366 - workspace-scoped companion of {@link #getUsageHistory(Long, String, Pageable)}. */
    @Transactional(readOnly = true)
    public Page<CreditLedgerEntry> getUsageHistoryByType(Long userId, String sourceType, String orgId, Pageable pageable) {
        Long payerUserId = resolvePayer(userId);
        if (payerUserId == null || payerUserId.equals(userId)) {
            return ledgerRepository.findByUserIdAndSourceTypeOrderByCreatedAtDesc(userId, sourceType, orgId, pageable);
        }
        return ledgerRepository.findByPayerAndExecutorAndSourceTypeOrderByCreatedAtDesc(payerUserId, userId, sourceType, orgId, pageable);
    }

    public Map<String, Object> getUsageSummary(Long userId) {
        return getUsageSummary(userId, null);
    }

    /**
     * V366 (ADR-0010) - workspace-scoped summary. When {@code orgId} is non-null,
     * the consumption breakdown + 30-day total cover only that workspace's
     * tagged rows. When null, they cover ALL workspaces (incl. unattributed
     * NULL-org rows) - the "All workspaces" view + legacy callers.
     *
     * <p>The {@code balance} field stays the payer's single wallet REGARDLESS of
     * orgId: owner-pays means there is one wallet, shown identically in every
     * workspace. Only the usage slice is per-workspace.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUsageSummary(Long userId, String orgId) {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        // 2026-05-22 user-reported fix - workspace-aware scope (mirror of
        // getUsageHistory above). Owner sees workspace-wide aggregates;
        // member sees only their executions in the current workspace.
        // Balance is intentionally kept payer-scoped via getBalance(userId)
        // below - that's the workspace's wallet (which IS the owner's in
        // TEAM context, the user's own in personal context).
        Long payerUserId = resolvePayer(userId);
        List<Object[]> summary;
        if (payerUserId == null || payerUserId.equals(userId)) {
            summary = ledgerRepository.getConsumptionSummary(userId, thirtyDaysAgo, orgId);
        } else {
            summary = ledgerRepository.getConsumptionSummaryByPayerAndExecutor(payerUserId, userId, thirtyDaysAgo, orgId);
        }

        BigDecimal balance = getBalance(userId);
        BigDecimal totalConsumed = BigDecimal.ZERO;
        Map<String, Map<String, Object>> byType = new HashMap<>();

        for (Object[] row : summary) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            BigDecimal total = (BigDecimal) row[2];
            totalConsumed = totalConsumed.add(total);
            byType.put(type, Map.of("count", count, "credits", total));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("balance", balance);
        result.put("totalConsumedLast30Days", totalConsumed);
        result.put("breakdownByType", byType);
        result.put("delinquent", isDelinquent(userId));
        return result;
    }

    public Map<String, Object> getUsageAnalytics(Long userId, int days,
                                                   String sourceType, String provider, String model) {
        return getUsageAnalytics(userId, days, sourceType, provider, model, null);
    }

    /**
     * V366 (ADR-0010) - workspace-scoped analytics. When {@code orgId} is
     * non-null, the daily chart + distinct provider/model/source-type lists
     * cover only that workspace's tagged rows. When null, they cover ALL
     * workspaces (incl. unattributed NULL-org rows) - the "All workspaces" view
     * + legacy callers.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUsageAnalytics(Long userId, int days,
                                                   String sourceType, String provider, String model,
                                                   String orgId) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        boolean hasFilters = sourceType != null || provider != null || model != null;
        // 2026-05-22 user-reported fix - workspace-aware scope (mirror of
        // getUsageHistory above). Owner sees workspace-wide chart; member
        // sees only their executions in the current workspace.
        Long payerUserId = resolvePayer(userId);
        boolean isOwnerView = payerUserId == null || payerUserId.equals(userId);
        List<Object[]> dailyRows;
        if (isOwnerView) {
            dailyRows = hasFilters
                    ? ledgerRepository.getDailyUsageFiltered(userId, from, sourceType, provider, model, orgId)
                    : ledgerRepository.getDailyUsageByType(userId, from, orgId);
        } else {
            dailyRows = hasFilters
                    ? ledgerRepository.getDailyUsageFilteredForPayerAndExecutor(payerUserId, userId, from, sourceType, provider, model, orgId)
                    : ledgerRepository.getDailyUsageByTypeForPayerAndExecutor(payerUserId, userId, from, orgId);
        }

        List<Map<String, Object>> dailyUsage = dailyRows.stream().map(row -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("date", row[0].toString());
            entry.put("sourceType", row[1]);
            entry.put("count", row[2]);
            entry.put("credits", row[3]);
            entry.put("tokens", row[4]);
            return entry;
        }).toList();

        List<String> providers = isOwnerView
                ? ledgerRepository.getDistinctProviders(userId, from, orgId)
                : ledgerRepository.getDistinctProvidersForPayerAndExecutor(payerUserId, userId, from, orgId);
        List<String> models = isOwnerView
                ? ledgerRepository.getDistinctModels(userId, from, orgId)
                : ledgerRepository.getDistinctModelsForPayerAndExecutor(payerUserId, userId, from, orgId);
        List<String> sourceTypes = isOwnerView
                ? ledgerRepository.getDistinctSourceTypes(userId, from, orgId)
                : ledgerRepository.getDistinctSourceTypesForPayerAndExecutor(payerUserId, userId, from, orgId);

        Map<String, Object> result = new HashMap<>();
        result.put("dailyUsage", dailyUsage);
        result.put("providers", providers);
        result.put("models", models);
        result.put("sourceTypes", sourceTypes);
        return result;
    }

    /**
     * Get total cost and breakdown for a specific workflow run.
     * Uses sourceId prefix matching to aggregate regular workflow-node IDs
     * ({@code runId:*}) plus web-search workflow IDs
     * ({@code web-search:RUN:<runId>:*}) and web-fetch workflow IDs
     * ({@code web-fetch:RUN:<runId>:*}).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getRunCostSummary(Long userId, String runId) {
        String runIdPrefix = runId + ":%";
        String webSearchRunIdPrefix = com.apimarketplace.common.credit.SourceIdBuilder.WEB_SEARCH_PREFIX
                + ":RUN:" + runId + ":%";
        String webFetchRunIdPrefix = com.apimarketplace.common.credit.SourceIdBuilder.WEB_FETCH_PREFIX
                + ":RUN:" + runId + ":%";
        Long payerUserId = resolvePayer(userId);
        BigDecimal totalCost = ledgerRepository.sumCostByRunIdIncludingWebSearch(
                payerUserId, runIdPrefix, webSearchRunIdPrefix, webFetchRunIdPrefix);
        List<Object[]> breakdown = ledgerRepository.getCostBreakdownByRunIdIncludingWebSearch(
                payerUserId, runIdPrefix, webSearchRunIdPrefix, webFetchRunIdPrefix);

        Map<String, Map<String, Object>> byType = new HashMap<>();
        long totalTokens = 0;
        for (Object[] row : breakdown) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            BigDecimal cost = (BigDecimal) row[2];
            Long promptTokens = (Long) row[3];
            Long completionTokens = (Long) row[4];
            totalTokens += promptTokens + completionTokens;
            byType.put(type, Map.of(
                "count", count,
                "cost", cost,
                "promptTokens", promptTokens,
                "completionTokens", completionTokens
            ));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("runId", runId);
        result.put("totalCost", totalCost);
        result.put("totalTokens", totalTokens);
        result.put("breakdownByType", byType);
        return result;
    }

    // ---- Private helpers ----

    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private static String truncateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            return description.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        return description;
    }

    /**
     * PR8 round-2 fix (audit B 2026-05-12, MUST-FIX #3) - length-safe
     * executor-audit suffix append. Base descriptions can already approach the
     * 500-char column cap (long model names, large image-count rows). A naive
     * {@code desc += " [executed by user #N]"} followed by
     * {@link #truncateDescription} silently dropped the suffix when the base
     * was already near the cap, losing the only on-row record of who actually
     * fired the consume.
     *
     * <p>Contract: when {@code payerUserId != executorUserId} (i.e. a member's
     * consume was redirected to the owner per Q1=b), this helper truncates the
     * BASE first to leave exact room for the full suffix, then concatenates.
     * The audit suffix is therefore guaranteed to be present on every redirect
     * row, at the cost of (rarely) truncating the base.
     *
     * <p>When {@code payerUserId.equals(executorUserId)} this is a no-op
     * passthrough - no redirect, no executor breadcrumb needed.
     */
    static String appendExecutorAudit(String baseDescription, Long executorUserId, Long payerUserId) {
        if (executorUserId == null || payerUserId == null || executorUserId.equals(payerUserId)) {
            return baseDescription;
        }
        String suffix = String.format(" [executed by user #%d]", executorUserId);
        if (baseDescription == null) return suffix.trim();
        int roomForBase = MAX_DESCRIPTION_LENGTH - suffix.length();
        if (roomForBase <= 0) return suffix.substring(0, Math.min(suffix.length(), MAX_DESCRIPTION_LENGTH));
        String base = baseDescription.length() > roomForBase
                ? baseDescription.substring(0, roomForBase)
                : baseDescription;
        return base + suffix;
    }

    /**
     * 10-arg overload - pre-flight semantics (insufficient balance blocks
     * the debit and writes a {@code <sourceType>_REJECTED} audit row).
     * Last parameter is the EXECUTOR (the user that fired the consume,
     * pre-owner-pays-redirect). Used by all pre-flight callers (workflow node,
     * web search, image generation, marketplace purchase, agent execution,
     * platform markup).
     */
    private CreditConsumeResult deductCredits(Long userId, BigDecimal cost, String sourceType,
                                               String sourceId, String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               String description, Long executorUserId) {
        return deductCredits(userId, cost, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, /*cachedTokens*/ null, description, false, executorUserId);
    }

    /**
     * Core variant adding {@code cachedTokens} and {@code allowNegative}.
     *
     * <p>{@code cachedTokens} is the cache-read token subset of
     * {@code promptTokens} for LLM rows (provider-agnostic
     * {@code max(cachedTokens, cacheReadTokens)} from {@link LlmTokenBreakdown}),
     * persisted to {@code credit_ledger.cached_tokens} so the usage-history UI can
     * show the cached share of a turn. It is a SUBSET of {@code promptTokens},
     * never additive, and does not affect the billed amount (the cache discount
     * is already baked into {@code cost} by {@link ModelPricingService}). NULL for
     * non-LLM debits (workflow node, image, web search, markup).
     *
     * <p>When {@code allowNegative} is {@code true}, the insufficient-balance
     * branch is bypassed and the debit proceeds even if the resulting balance is
     * < 0. Reserved for POST-FLIGHT call sites where the cost has already been
     * incurred by the platform (e.g. chat: the LLM has already streamed). The
     * new-conversation gate ({@link #canAfford}) still refuses fresh starts when
     * the projected cost exceeds the balance, so a negative balance only absorbs
     * an in-flight overshoot.
     *
     * <p>{@code executorUserId} (PR11) is the user that fired the consume,
     * separate from {@code userId} (= payer, post owner-pays redirect). Persisted
     * to {@code credit_ledger.executor_user_id} for per-member quota cap
     * enforcement. When null, defaults to {@code userId} (defence in depth -
     * shouldn't happen on the redirect path but keeps legacy/test paths
     * insert-safe).
     */
    private CreditConsumeResult deductCredits(Long userId, BigDecimal cost, String sourceType,
                                               String sourceId, String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               Integer cachedTokens,
                                               String description, boolean allowNegative,
                                               Long executorUserId) {
        Long effectiveExecutor = executorUserId != null ? executorUserId : userId;
        // V366 (ADR-0010): capture the active workspace once so every ledger row
        // this debit writes (zero-cost, unlimited, rejection, normal) carries the
        // same reporting tag. Null when no active workspace context.
        String ledgerOrgId = currentLedgerOrgId();
        if (cost.compareTo(BigDecimal.ZERO) <= 0) {
            // Historically hit by bridge providers (V117 seed: rates=0). Post-V130
            // bridges bill at cloud rates so this branch is now a narrow edge case -
            // pricing anomalies (missing row, zero-rate config, pricing bugs) that
            // still produce cost==0. We keep the zero-amount ledger write when tokens
            // were actually consumed so Finance retains an audit trail. Guard on
            // tokens>0 so genuine no-op calls (no provider context) still skip the
            // write; guard on cost==0 so negative-cost anomalies stay out of the ledger.
            // Applies in both normal and unlimited modes.
            boolean hasTokenUsage =
                    (promptTokens != null && promptTokens > 0)
                    || (completionTokens != null && completionTokens > 0);
            boolean costIsExactlyZero = cost.signum() == 0;
            if (hasTokenUsage && costIsExactlyZero) {
                BigDecimal balance = unlimited ? UNLIMITED_BALANCE : getBalance(userId);
                CreditLedgerEntry entry = new CreditLedgerEntry();
                entry.setUserId(userId);
                entry.setExecutorUserId(effectiveExecutor);  // PR11 - quota enforcement key
                entry.setOrganizationId(ledgerOrgId);        // V366 - workspace reporting tag
                entry.setAmount(BigDecimal.ZERO);
                entry.setBalanceAfter(balance);
                entry.setSourceType(sourceType);
                entry.setSourceId(sourceId);
                entry.setProvider(provider);
                entry.setModel(model);
                entry.setPromptTokens(promptTokens);
                entry.setCompletionTokens(completionTokens);
                entry.setCachedTokens(cachedTokens);
                entry.setDescription(truncateDescription(description));
                ledgerRepository.save(entry);
                log.debug("Recorded zero-cost {} usage for user {} ({}/{}) - bridge/flat-rate row",
                        sourceType, userId, provider, model);
                return CreditConsumeResult.success(BigDecimal.ZERO, balance);
            }
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(userId));
        }

        // Unlimited mode: track consumption in ledger but never deduct from balance
        if (unlimited) {
            CreditLedgerEntry entry = new CreditLedgerEntry();
            entry.setUserId(userId);
            entry.setExecutorUserId(effectiveExecutor);  // PR11 - quota enforcement key
            entry.setOrganizationId(ledgerOrgId);        // V366 - workspace reporting tag
            entry.setAmount(cost.negate());
            entry.setBalanceAfter(UNLIMITED_BALANCE);
            entry.setSourceType(sourceType);
            entry.setSourceId(sourceId);
            entry.setProvider(provider);
            entry.setModel(model);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setCachedTokens(cachedTokens);
            entry.setDescription(truncateDescription(description));
            ledgerRepository.save(entry);

            log.debug("Tracked {} credits for user {} ({}) [unlimited mode]", cost, userId, sourceType);
            return CreditConsumeResult.success(cost, UNLIMITED_BALANCE);
        }

        Subscription sub = findSubscriptionForUpdate(userId);
        if (sub == null) {
            log.warn("No active subscription for user {}. Skipping credit deduction.", userId);
            return CreditConsumeResult.noSubscription();
        }

        // PR11d-a (TOCTOU race fix, audit B 2026-05-12 MUST-FIX #1):
        // The cap check NOW runs INSIDE the same transaction as the debit,
        // AFTER findSubscriptionForUpdate has taken a PESSIMISTIC_WRITE lock
        // on the payer's subscription row. Two concurrent consumes by the
        // same member serialize on the owner's subscription lock, so the
        // SUM(credit_ledger) read in checkCreditsCap sees committed state
        // from the previous winner before this branch runs. Prior shape
        // (cap check OUTSIDE deductCredits in a separate readOnly tx) let
        // 2 parallel consumes BOTH pass the check before either had debited.
        CreditConsumeResult capRefused = enforceQuotaCap(effectiveExecutor, cost, userId);
        if (capRefused != null) return capRefused;

        // Free workflow-credit scoping (Cloud only): on the FREE plan the monthly
        // sub bucket funds ONLY workflow-node orchestration; every other source
        // type draws the PAYG bucket alone. subBucketEligible short-circuits to
        // true under unlimited (CE) and for paid plans, so currentBalance ==
        // availableBalance there and this is a behavioural no-op.
        boolean subEligible = subBucketEligible(sub, sourceType);
        BigDecimal currentBalance = sub.getTotalBalance();
        BigDecimal availableBalance = subEligible ? currentBalance : sub.getPaygRemainingCredits();
        if (availableBalance.compareTo(cost) < 0 && !allowNegative) {
            log.warn("User {} has insufficient credits: available={} (sub={}, payg={}, subEligible={}), cost={}. Blocking deduction.",
                    userId, availableBalance, sub.getRemainingCredits(), sub.getPaygRemainingCredits(), subEligible, cost);
            // Audit-trail row for the rejected consumption. amount=0 keeps the
            // ledger invariant (sum(ledger) == balance changes) intact - the debit never
            // happened - while preserving the provider/model/token-count context that
            // would otherwise vanish. The 'X_REJECTED' suffix lets Finance filter these
            // out of real consumption queries while still aggregating them for abuse
            // detection and rejection analytics. Same tx as the balance check: if the
            // save throws, the caller still receives insufficientCredits (best-effort
            // audit, never hides the primary signal).
            try {
                CreditLedgerEntry rejected = new CreditLedgerEntry();
                rejected.setUserId(userId);
                rejected.setExecutorUserId(effectiveExecutor);  // PR11 - quota enforcement key
                rejected.setOrganizationId(ledgerOrgId);        // V366 - workspace reporting tag
                rejected.setAmount(BigDecimal.ZERO);
                rejected.setBalanceAfter(availableBalance);
                rejected.setSourceType(sourceType + "_REJECTED");
                rejected.setSourceId(sourceId);
                rejected.setProvider(provider);
                rejected.setModel(model);
                rejected.setPromptTokens(promptTokens);
                rejected.setCompletionTokens(completionTokens);
                rejected.setCachedTokens(cachedTokens);
                rejected.setDescription(truncateDescription(
                    "REJECTED: attempted " + cost + " credits, balance " + availableBalance
                    + (description != null ? " - " + description : "")));
                ledgerRepository.save(rejected);
            } catch (Exception e) {
                log.warn("Failed to write rejection audit row for user {} source={}: {}",
                        userId, sourceType, e.getMessage());
            }
            return CreditConsumeResult.insufficientCredits(availableBalance, cost);
        }

        // V250: apply debit across sub + payg buckets. The new total balance
        // can go negative under the allowNegative branch (chat post-flight
        // overshoot). When negative, drain sub fully then drain payg. When the
        // sub bucket is not eligible (FREE non-workflow), the whole debit routes
        // to PAYG so the monthly workflow grant stays untouched.
        applyDebit(sub, cost, subEligible);
        BigDecimal newBalance = sub.getTotalBalance();
        // Free workflow-credit scoping: a PAYG-routed overshoot (!subEligible,
        // allowNegative post-flight) drives the PAYG bucket negative while the
        // monthly workflow grant keeps the TOTAL positive. That debt is just as
        // real as a negative total - the sub bucket can never repay it (it is
        // not eligible for this source type). Without this branch the delinquent
        // gate never fired for Free accounts: each chat turn's overshoot was
        // unbounded and repeatable as long as the monthly grant masked the total.
        boolean paygOwed = !subEligible && sub.getPaygRemainingCredits().signum() < 0;
        if (newBalance.signum() < 0 || paygOwed) {
            log.warn("User {} {} debited despite insufficient credits: totalBalance went {} -> {} (cost={}, sub={}, payg={}, paygOwed={}). " +
                            "allowNegative=true post-flight reconciliation.",
                    userId, sourceType, currentBalance, newBalance, cost,
                    sub.getRemainingCredits(), sub.getPaygRemainingCredits(), paygOwed);
            // Honour the V148 invariant (V250 2-bucket, extended for Free scoping):
            //   delinquent = TRUE  ⇒  (total ≤ 0  OR  (FREE plan AND payg < 0))
            // (paygOwed can only be true on FREE - !subEligible never holds on
            // paid plans - so this branch never widens paid-plan delinquency.)
            // Setting the flag now blocks any subsequent reserve / workflow-init via
            // {@link #tryReserveMarkup} until the user tops up - preventing unbounded
            // debt accumulation through new workflow runs while a chat overshoot is
            // unresolved. {@link #clearDelinquentIfPositive} flips it back when the
            // next grant/purchase settles both buckets.
            if (!unlimited) sub.setDelinquent(true);
        }
        subscriptionRepository.save(sub);

        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(userId);
        entry.setExecutorUserId(effectiveExecutor);  // PR11 - quota enforcement key
        entry.setOrganizationId(ledgerOrgId);        // V366 - workspace reporting tag
        entry.setAmount(cost.negate());
        entry.setBalanceAfter(newBalance);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setProvider(provider);
        entry.setModel(model);
        entry.setPromptTokens(promptTokens);
        entry.setCompletionTokens(completionTokens);
        entry.setCachedTokens(cachedTokens);
        entry.setDescription(truncateDescription(description));
        ledgerRepository.save(entry);

        log.info("Deducted {} credits from user {} ({}). Balance: {} -> {}", cost, userId, sourceType, currentBalance, newBalance);
        return CreditConsumeResult.success(cost, newBalance);
    }

    /**
     * Acquire a PESSIMISTIC_WRITE on the active subscription row for credit
     * debit/grant paths. The caller is responsible for passing the PAYER's
     * userId (already resolved via {@link #resolvePayer} where applicable):
     *
     * <ul>
     *   <li>{@code consumeFor*} → caller pre-resolved the payer and passed
     *       it as {@code userId} to {@link #deductCredits}. {@code userId}
     *       IS the payer.</li>
     *   <li>{@link #consumeForMarketplacePurchase} → marketplace explicitly
     *       bypasses owner-pays redirect; the purchasing user self-pays.
     *       {@code userId} IS the payer (executor).</li>
     *   <li>{@link #grantCredits} → caller passes the recipient userId
     *       directly. The recipient IS the wallet owner.</li>
     * </ul>
     *
     * <p>Re-resolving here would be incorrect: it would redirect the
     * marketplace bypass to the workspace owner's wallet, defeating the
     * explicit bypass.
     */
    private Subscription findSubscriptionForUpdate(Long userId) {
        if (userId == null) return null;
        return subscriptionRepository.findActiveByUserIdForUpdate(userId).orElse(null);
    }

    // ============================================================================
    // ===== V148+ Reservation lifecycle (replaces consumeForImageGeneration*) =====
    // ============================================================================
    //
    // Three atomic operations - reserve / commit / release - that share ONE
    // ledger row per call (sourceId-pre IS sourceId-final). The row's
    // {@code source_type} flips through the lifecycle:
    //
    //   tryReserveMarkup    → INSERT  source_type='PLATFORM_MARKUP_RESERVE'
    //   commitReservation   → UPDATE  source_type='PLATFORM_MARKUP'         (or _PARTIAL/_FLOORED via outcome)
    //   releaseReservation  → UPDATE  source_type='PLATFORM_MARKUP_RELEASED'
    //   sweeper auto-release → same as releaseReservation but reason marker
    //                         and final source_type=PLATFORM_MARKUP_RELEASED_TIMEOUT
    //
    // Invariant maintained by clearDelinquentIfPositive:
    //   delinquent = TRUE  ⇒  remainingCredits ≤ 0
    // ============================================================================

    /**
     * Outcome of {@link #commitReservation}. Catalog observers map each variant
     * to a metric / log level / paging policy:
     * <ul>
     *   <li>{@link #COMMITTED} - happy path, log info.</li>
     *   <li>{@link #ALREADY_COMMITTED} - idempotent retry, log info, metric only.</li>
     *   <li>{@link #RESERVATION_EXPIRED} - sweeper auto-released before commit
     *       reached us; PagerDuty (rate-based) because TTL ought to exceed
     *       upstream timeout.</li>
     *   <li>{@link #COMMITTED_PARTIAL} - user balance covered ≥0 but {@code <actual};
     *       we charged {@code max_chargeable}, set {@code delinquent=true},
     *       PagerDuty + dedup by userId.</li>
     *   <li>{@link #COMMITTED_FLOORED} - balance was already negative from a
     *       concurrent partial-charge; we charged {@code reserved} only (no
     *       further debit), set {@code delinquent=true}, PagerDuty.</li>
     * </ul>
     */
    public enum CommitOutcome {
        COMMITTED, ALREADY_COMMITTED, RESERVATION_EXPIRED, COMMITTED_PARTIAL, COMMITTED_FLOORED
    }

    /**
     * Outcome of {@link #releaseReservation}.
     * <ul>
     *   <li>{@link #RELEASED} - happy path, refund credited.</li>
     *   <li>{@link #ALREADY_RELEASED} - idempotent retry.</li>
     *   <li>{@link #ALREADY_COMMITTED} - race with commit; commit won, no refund
     *       (commit already reconciled balance). Caller logs metric only.</li>
     * </ul>
     */
    public enum ReleaseOutcome {
        RELEASED, ALREADY_RELEASED, ALREADY_COMMITTED
    }

    /**
     * Pre-flight reservation: write a {@code PLATFORM_MARKUP_RESERVE} ledger row
     * with negative amount and reduce balance by {@code projected}. Done inside
     * {@code findSubscriptionForUpdate} row lock so concurrent reserve+spend
     * paths can't double-debit.
     *
     * <p><b>Idempotency</b> via {@code sourceId} unique index: a duplicate
     * {@code tryReserveMarkup} for the same sourceId returns
     * {@link CreditConsumeResult#success} without re-debiting (catches
     * {@link DataIntegrityViolationException} on SQLState 23505 + constraint
     * matching {@code *source_id*}).
     *
     * <p><b>Delinquency gate</b>: if {@code subscription.delinquent=true}:
     * <ul>
     *   <li>{@code scopeKind="RUN"} + existing pin (workflow run already
     *       started, sub-allocation): bypass - atomicity preferred over gate.</li>
     *   <li>{@code scopeKind="STREAM"}: refuse (chat enforces gate every call).</li>
     *   <li>{@code scopeKind="RUN"} fresh / sourceId starts with
     *       {@code platform-markup:INIT:}: refuse (workflow can't START while delinquent).</li>
     *   <li>{@code scopeKind="LEGACY"} (controller default when caller passes
     *       null scopeKind - non-RUN, non-STREAM context): bypass. Catalog
     *       paths that don't have a run-id or stream-id (e.g. one-shot tool
     *       calls) end up here and would otherwise be blocked by a
     *       delinquent owner gate they cannot retry from.</li>
     * </ul>
     *
     * <p><b>CE compat</b>: {@code markupEnabled=false} returns success with
     * zero amount and writes nothing. CE never observes a {@code _RESERVE} row.
     *
     * @param sourceId       canonical {@code platform-markup:*} key from
     *                       {@link com.apimarketplace.common.credit.SourceIdBuilder}.
     * @param ttlMinutes     reservation lifetime; sweeper auto-releases past this.
     * @param scopeKind      {@code "RUN" | "STREAM" | "LEGACY"}.
     * @param scopeId        run-id, stream-id, or null for LEGACY
     * @param hasExistingPin true iff caller already verified
     *                       {@code pinService.lookupPin(...)} returned non-empty;
     *                       drives the in-flight RUN bypass branch.
     */
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public CreditConsumeResult tryReserveMarkup(Long executorUserId, String sourceId,
                                                  String provider, String model,
                                                  BigDecimal projected, Long pinId,
                                                  int ttlMinutes,
                                                  String scopeKind, String scopeId,
                                                  boolean hasExistingPin) {
        if (!markupEnabled) {
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(executorUserId));
        }
        if (projected == null || projected.signum() <= 0) {
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalance(executorUserId));
        }
        if (ttlMinutes <= 0 || ttlMinutes > 1440) {
            log.warn("Invalid ttlMinutes={} for reserve sourceId={} - clamping to 15", ttlMinutes, sourceId);
            ttlMinutes = 15;
        }

        // Owner-pays: redirect to the workspace owner's wallet before any
        // subscription lock / debit / ledger write. Reserve + commit + release
        // all key off the row's user_id (= payer), so resolving once at entry
        // makes the entire lifecycle consistent. The cap-enforcement sum query
        // attributes consumption to executor_user_id (= executorUserId).
        Long payerUserId = resolvePayer(executorUserId);

        // Idempotency fast-path
        if (sourceId != null && ledgerRepository.existsBySourceId(sourceId)) {
            log.debug("Reservation already exists for sourceId={}, idempotent success", sourceId);
            return CreditConsumeResult.success(BigDecimal.ZERO, getBalanceForSelf(payerUserId));
        }

        Subscription sub = findSubscriptionForUpdate(payerUserId);
        if (sub == null) {
            return CreditConsumeResult.noSubscription();
        }

        // Delinquency gate (workflow vs chat semantics)
        // scopeKind taxonomy:
        //   LEGACY        → bypass (catalog tools without run/stream context)
        //   RUN + pin     → bypass (in-flight workflow finishes atomically)
        //   RUN fresh/INIT → refuse (workflow can't START while delinquent)
        //   STREAM        → refuse (chat enforces gate every call)
        //   any other     → refuse (default-safe; null scopeKind callers must
        //                   opt-in to LEGACY explicitly via the controller)
        if (Boolean.TRUE.equals(sub.getDelinquent())) {
            boolean isLegacy = "LEGACY".equals(scopeKind);
            boolean isInFlightRun = "RUN".equals(scopeKind) && hasExistingPin
                    && (sourceId == null
                        || !sourceId.startsWith(com.apimarketplace.common.credit.SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":INIT:"));
            if (!isLegacy && !isInFlightRun) {
                log.info("Refusing reserve for delinquent payer {} (executor={}, scopeKind={}, hasPin={})",
                        payerUserId, executorUserId, scopeKind, hasExistingPin);
                return CreditConsumeResult.delinquentRefused(getBalanceForSelf(payerUserId));
            }
        }

        // V250: read total balance (sub + payg). The PAYG bucket extends the
        // user's spendable amount past the sub quota for the current cycle.
        // Free workflow-credit scoping (Cloud only): platform markup is never
        // workflow-node orchestration, so on the FREE plan it draws the PAYG
        // bucket alone (availableBalance), leaving the monthly grant untouched.
        // No-op for paid plans / CE (subEligible == true -> available == total).
        boolean subEligible = subBucketEligible(sub, "PLATFORM_MARKUP");
        BigDecimal currentBalance = sub.getTotalBalance();
        BigDecimal availableBalance = subEligible ? currentBalance : sub.getPaygRemainingCredits();
        BigDecimal newBalance = currentBalance.subtract(projected);

        // Unlimited mode still writes the row (audit trail) but balance stays untouched
        if (unlimited) {
            CreditLedgerEntry entry = buildReserveEntry(payerUserId, executorUserId, sourceId,
                    provider, model, projected, UNLIMITED_BALANCE, pinId, ttlMinutes);
            try {
                ledgerRepository.save(entry);
            } catch (DataIntegrityViolationException dive) {
                if (isDuplicateSourceId(dive)) {
                    return CreditConsumeResult.success(BigDecimal.ZERO, UNLIMITED_BALANCE);
                }
                throw dive;
            }
            return CreditConsumeResult.success(projected, UNLIMITED_BALANCE);
        }

        // Insufficient balance: write rejection audit + return failure (no reserve row).
        // Checked against availableBalance so a FREE account with monthly workflow
        // credits but no PAYG top-up is correctly refused a platform-markup reserve.
        if (availableBalance.subtract(projected).signum() < 0) {
            log.info("Insufficient balance for reserve payer={} executor={} sourceId={} available={} projected={} subEligible={}",
                    payerUserId, executorUserId, sourceId, availableBalance, projected, subEligible);
            return CreditConsumeResult.insufficientCredits(availableBalance, projected);
        }

        // V250: apply debit across sub + payg buckets (sub drained first,
        // payg fills the gap). newBalance == sub.getTotalBalance() post-call.
        // V254: capture the actual PAYG portion so the matching refund (commit
        // delta or release) can credit the right bucket symmetrically.
        BigDecimal subBefore = sub.getRemainingCredits();
        BigDecimal paygBefore = sub.getPaygRemainingCredits();
        BucketSplit split = applyDebit(sub, projected, subEligible);
        subscriptionRepository.save(sub);

        CreditLedgerEntry entry = buildReserveEntry(payerUserId, executorUserId, sourceId,
                provider, model, projected, newBalance, pinId, ttlMinutes);
        entry.setPaygPortion(split.fromPayg());
        try {
            ledgerRepository.save(entry);
        } catch (DataIntegrityViolationException dive) {
            if (isDuplicateSourceId(dive)) {
                // Race with another reserve for same sourceId - undo our balance debit
                // (the winner debited theirs). Restore the bucket state we captured
                // pre-debit (both sub and payg) so the rollback is per-bucket
                // accurate, not just the sub bucket.
                Subscription latest = findSubscriptionForUpdate(payerUserId);
                if (latest != null) {
                    latest.setRemainingCredits(subBefore);
                    latest.setPaygRemainingCredits(paygBefore);
                    subscriptionRepository.save(latest);
                }
                return CreditConsumeResult.success(BigDecimal.ZERO, currentBalance);
            }
            throw dive;
        }
        return CreditConsumeResult.success(projected, newBalance);
    }

    private CreditLedgerEntry buildReserveEntry(Long payerUserId, Long executorUserId,
                                                 String sourceId, String provider, String model,
                                                 BigDecimal projected, BigDecimal balanceAfter,
                                                 Long pinId, int ttlMinutes) {
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(payerUserId);
        entry.setExecutorUserId(executorUserId);
        // V366 - workspace reporting tag, captured at reserve (preflight) time. The
        // later commit mutates this same row in place, so the tag persists onto the
        // final PLATFORM_MARKUP row.
        entry.setOrganizationId(currentLedgerOrgId());
        entry.setAmount(projected.negate());
        entry.setBalanceAfter(balanceAfter);
        entry.setSourceType("PLATFORM_MARKUP_RESERVE");
        entry.setSourceId(sourceId);
        entry.setProvider(provider);
        entry.setModel(model);
        entry.setPinId(pinId);
        entry.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        entry.setDescription(truncateDescription("Markup reservation: " + provider + "/" + model));
        return entry;
    }

    /**
     * Commit a previously-reserved markup. Atomically:
     * <ol>
     *   <li>Reads the {@code _RESERVE} row by sourceId under
     *       {@code findSubscriptionForUpdate} lock.</li>
     *   <li>Computes {@code delta = reserved - actual}.</li>
     *   <li>If {@code balance + delta >= 0}: happy path. UPDATE balance += delta,
     *       UPDATE row to {@code source_type='PLATFORM_MARKUP', amount=-actual,
     *       expires_at=NULL}. Returns {@link CommitOutcome#COMMITTED}.</li>
     *   <li>Else if {@code max_chargeable >= 0}: partial path. Charge what user
     *       can pay (drives balance to 0), set {@code delinquent=true}.
     *       Returns {@link CommitOutcome#COMMITTED_PARTIAL}.</li>
     *   <li>Else (balance &lt; 0 from concurrent over-debit): floored path.
     *       Charge {@code reserved} only (no further debit), set
     *       {@code delinquent=true}. Returns {@link CommitOutcome#COMMITTED_FLOORED}.</li>
     * </ol>
     * Idempotent: re-running on a row already {@code PLATFORM_MARKUP} returns
     * {@link CommitOutcome#ALREADY_COMMITTED}; on {@code _RELEASED*} returns
     * {@link CommitOutcome#RESERVATION_EXPIRED}.
     */
    @Transactional
    public CommitOutcome commitReservation(String sourceId, BigDecimal actualAmount,
                                            String provider, String model) {
        if (!markupEnabled) return CommitOutcome.COMMITTED;
        if (sourceId == null || actualAmount == null || actualAmount.signum() < 0) {
            log.warn("Invalid commitReservation params: sourceId={} actual={}", sourceId, actualAmount);
            return CommitOutcome.RESERVATION_EXPIRED;
        }

        Optional<CreditLedgerEntry> rowOpt = findLedgerBySourceIdForLifecycleUpdate(sourceId);
        if (rowOpt.isEmpty()) return CommitOutcome.RESERVATION_EXPIRED;
        CreditLedgerEntry row = rowOpt.get();
        String st = row.getSourceType();
        if ("PLATFORM_MARKUP".equals(st)) return CommitOutcome.ALREADY_COMMITTED;
        if (st != null && st.startsWith("PLATFORM_MARKUP_RELEASED")) return CommitOutcome.RESERVATION_EXPIRED;
        if (!"PLATFORM_MARKUP_RESERVE".equals(st)) {
            log.warn("Unexpected source_type {} for commit sourceId={}", st, sourceId);
            return CommitOutcome.RESERVATION_EXPIRED;
        }

        Subscription sub = findSubscriptionForUpdate(row.getUserId());
        if (sub == null) return CommitOutcome.RESERVATION_EXPIRED;

        // Free workflow-credit scoping: any extra charge beyond the original
        // reserve (actual > reserved) must drain the same PAYG-only bucket the
        // reserve used on a FREE account. No-op for paid plans / CE.
        boolean subEligible = subBucketEligible(sub, "PLATFORM_MARKUP");

        BigDecimal reserved = row.getAmount().abs();          // positive
        // V250: use total balance (sub + payg) for the chargeable arithmetic.
        // The reserve was applied via splitBuckets, so the total balance post-
        // reserve correctly reflects the user's spending authority.
        BigDecimal balanceAtCommit = sub.getTotalBalance();   // post-reserve total
        BigDecimal maxChargeable = balanceAtCommit.add(reserved); // = pre-reserve total

        BigDecimal finalCharge;
        BigDecimal newBalance;
        CommitOutcome outcome;

        // V254: original PAYG portion of the reserve; reused to credit the
        // matching bucket on under-shoot refunds. Pre-V254 reserve rows carry
        // 0 - those fall back to refund-to-sub (legacy semantics).
        BigDecimal reservedPaygPortion = row.getPaygPortion();

        if (maxChargeable.compareTo(actualAmount) >= 0) {
            // happy path: refund (reserved - actualAmount), which can be positive
            // (typical: under-shot) or negative (rare: over-shot but covered).
            finalCharge = actualAmount;
            BigDecimal refundDelta = reserved.subtract(actualAmount);
            if (!unlimited) {
                if (refundDelta.signum() > 0) {
                    // Refund PAYG portion proportionally to refundDelta vs reserved
                    // so a partial under-shoot returns the right fraction to each
                    // bucket. Example: reserved=100 (payg=40), actual=70 → refund
                    // 30; payg slice = 40 × 30/100 = 12 returns to PAYG, 18 to sub.
                    BigDecimal refundPaygSlice = reserved.signum() == 0
                            ? BigDecimal.ZERO
                            : reservedPaygPortion.multiply(refundDelta)
                                    .divide(reserved, 4, java.math.RoundingMode.HALF_UP);
                    applyRefund(sub, refundDelta, refundPaygSlice);
                } else if (refundDelta.signum() < 0) {
                    // actual > reserved but covered by remaining balance - extra debit
                    applyDebit(sub, refundDelta.negate(), subEligible);
                }
            }
            newBalance = unlimited ? UNLIMITED_BALANCE : sub.getTotalBalance();
            outcome = CommitOutcome.COMMITTED;
        } else if (maxChargeable.signum() >= 0) {
            // partial-charge: drive total balance to 0 by refunding (or
            // debiting) the gap.
            finalCharge = maxChargeable;
            if (!unlimited) {
                BigDecimal gap = reserved.subtract(maxChargeable);
                if (gap.signum() >= 0) {
                    // reserved >= maxChargeable: we over-held; refund the
                    // excess. V254 proportional split - the gap may correspond
                    // to payg / sub mixed, refund symmetrically. If the refund
                    // is larger than the stored payg portion, the helper
                    // clamps and sends the overflow to sub.
                    BigDecimal refundPaygSlice = reserved.signum() == 0
                            ? BigDecimal.ZERO
                            : reservedPaygPortion.multiply(gap)
                                    .divide(reserved, 4, java.math.RoundingMode.HALF_UP);
                    applyRefund(sub, gap, refundPaygSlice);
                } else {
                    // reserved < maxChargeable: actual cost overshot what we
                    // already held - we still owe |gap| on top of the reserve
                    // to bring the balance to 0. applyRefund cannot handle
                    // negative amounts (its PAYG branch is gated on
                    // positive); use applyDebit so both buckets drain. The
                    // sub/payg split is decided by splitBuckets on the current
                    // bucket state, not by the original reservedPaygPortion.
                    applyDebit(sub, gap.negate(), subEligible);
                }
                // Honour V148 invariant - delinquent ⇒ totalBalance ≤ 0.
                // At this point totalBalance should be 0; set delinquent to
                // gate future reserves until top-up.
                sub.setDelinquent(true);
            }
            newBalance = unlimited ? UNLIMITED_BALANCE : sub.getTotalBalance();
            outcome = CommitOutcome.COMMITTED_PARTIAL;
            log.warn("Partial-charge commit user={} sourceId={} reserved={} actual={} maxChargeable={}",
                    row.getUserId(), sourceId, reserved, actualAmount, maxChargeable);
        } else {
            // floored: total balance was already negative from concurrent over-debit
            finalCharge = reserved;            // charge what the user already paid via reserve
            // No change to buckets - reserve already moved balance; this branch
            // documents the unbilled gap for finance, no further write.
            if (!unlimited) sub.setDelinquent(true);
            newBalance = unlimited ? UNLIMITED_BALANCE : balanceAtCommit;
            outcome = CommitOutcome.COMMITTED_FLOORED;
            BigDecimal unbilledDelta = actualAmount.subtract(reserved);
            log.warn("Floored commit user={} sourceId={} balanceAtCommit={} reserved={} actual={} unbilledDelta={} (platform ate the gap)",
                    row.getUserId(), sourceId, balanceAtCommit, reserved, actualAmount, unbilledDelta);
        }

        if (!unlimited) {
            subscriptionRepository.save(sub);
        }

        row.setSourceType("PLATFORM_MARKUP");
        row.setAmount(finalCharge.negate());
        row.setBalanceAfter(unlimited ? UNLIMITED_BALANCE : newBalance);
        row.setProvider(provider);
        row.setModel(model);
        row.setExpiresAt(null); // clear expiry on commit
        ledgerRepository.save(row);

        return outcome;
    }

    /**
     * Release an un-committed reservation (refund balance, flip row to
     * {@code PLATFORM_MARKUP_RELEASED}). Idempotent. Used by:
     * <ul>
     *   <li>{@code CatalogToolBillingService} on partial result (n=5 returns 3
     *       - release 2 unused per-callIndex rows)</li>
     *   <li>{@link com.apimarketplace.auth.service.PlatformMarkupReserveSweeper}
     *       for orphaned reservations past their {@code expires_at}</li>
     * </ul>
     */
    @Transactional
    public ReleaseOutcome releaseReservation(String sourceId, String reason) {
        if (!markupEnabled) return ReleaseOutcome.RELEASED;
        if (sourceId == null) return ReleaseOutcome.ALREADY_RELEASED;

        Optional<CreditLedgerEntry> rowOpt = findLedgerBySourceIdForLifecycleUpdate(sourceId);
        if (rowOpt.isEmpty()) return ReleaseOutcome.ALREADY_RELEASED;
        CreditLedgerEntry row = rowOpt.get();
        String st = row.getSourceType();
        if (st != null && st.startsWith("PLATFORM_MARKUP_RELEASED")) return ReleaseOutcome.ALREADY_RELEASED;
        if ("PLATFORM_MARKUP".equals(st)) return ReleaseOutcome.ALREADY_COMMITTED;
        if (!"PLATFORM_MARKUP_RESERVE".equals(st)) {
            log.warn("Unexpected source_type {} for release sourceId={}", st, sourceId);
            return ReleaseOutcome.ALREADY_RELEASED;
        }

        Subscription sub = findSubscriptionForUpdate(row.getUserId());
        if (sub == null) return ReleaseOutcome.ALREADY_RELEASED;

        BigDecimal reserved = row.getAmount().abs();
        if (!unlimited) {
            // V254: refund credits each bucket using the per-row payg_portion
            // that was stamped on the RESERVE insert. A PAYG-funded reservation
            // that expires therefore returns to the PAYG bucket - without this
            // the dollars would land on sub and get wiped at the next renewal.
            // Pre-V254 historical rows carry paygPortion=0 → fallback to the
            // legacy refund-to-sub behaviour.
            applyRefund(sub, reserved, row.getPaygPortion());
            clearDelinquentIfPositive(sub);
            subscriptionRepository.save(sub);
        }

        // Pick the final source_type based on reason (auto-release-timeout vs explicit)
        boolean isTimeout = reason != null && reason.startsWith("auto-release-timeout");
        row.setSourceType(isTimeout ? "PLATFORM_MARKUP_RELEASED_TIMEOUT" : "PLATFORM_MARKUP_RELEASED");
        // Keep row.amount = -reserved (preserves projection trace; sum-of-ledger
        // invariant maintained by balance refund on the subscription).
        row.setBalanceAfter(unlimited ? UNLIMITED_BALANCE : sub.getTotalBalance());
        row.setExpiresAt(null);
        if (reason != null) {
            // Preserve the original "Markup reservation: provider/model" trace by
            // appending the release reason rather than overwriting. Finance can
            // still see what was reserved AND why it ended.
            String existing = row.getDescription();
            String combined = (existing == null || existing.isBlank())
                    ? reason
                    : existing + " | " + reason;
            row.setDescription(truncateDescription(combined));
        }
        ledgerRepository.save(row);
        return ReleaseOutcome.RELEASED;
    }

    private Optional<CreditLedgerEntry> findLedgerBySourceIdForLifecycleUpdate(String sourceId) {
        Optional<CreditLedgerEntry> locked = ledgerRepository.findFirstBySourceIdForUpdate(sourceId);
        if (locked == null || locked.isEmpty()) {
            return ledgerRepository.findFirstBySourceId(sourceId);
        }
        return locked;
    }

    /**
     * Helper: clear the {@code delinquent} flag once the wallet no longer owes
     * anything: the TOTAL balance (sub + payg) is positive AND the PAYG bucket
     * is not negative. Invoked AFTER any positive-balance transition:
     * {@link #grantCredits}, {@link #releaseReservation}.
     *
     * <p>Rule is strictly {@code &gt; 0} on the total (zero does not clear) so a
     * user whose refund just zeros their account stays delinquent until they
     * actually top up with positive balance.
     *
     * <p>The {@code payg &gt;= 0} leg applies ONLY on the FREE plan
     * ({@link #isWorkflowCreditsOnlyPlan}) and mirrors the Free-scoping
     * delinquency set in {@link #deductCredits}: there, a chat overshoot leaves
     * the PAYG bucket negative while the monthly workflow grant keeps the total
     * positive, and the sub bucket can never repay that debt (it is not eligible
     * for chat). A monthly renewal must NOT clear it - only a PAYG top-up that
     * settles the negative bucket does. On PAID plans the leg is skipped: every
     * paid-plan debit nets against the two-bucket total, so a positive total
     * means any PAYG deficit is already recovered and the renewal grant clears
     * the flag exactly as pre-V379 (a paying customer with a positive balance
     * must never stay gated).
     *
     * <p>Invariant maintained (V250 2-bucket, extended for Free scoping):
     * {@code delinquent = TRUE ⇒ (total ≤ 0 OR (FREE plan AND payg_remaining_credits < 0))}.
     *
     * <p>Critical fix vs pre-V250: an apparently delinquent user who tops up
     * PAYG only (sub still 0, payg now positive) now clears their delinquent
     * flag and can resume reserves. Pre-V250 the flag stuck because the check
     * looked at remaining_credits only, blocking new chats despite a positive
     * total balance.
     */
    private void clearDelinquentIfPositive(Subscription sub) {
        if (sub == null) return;
        if (!Boolean.TRUE.equals(sub.getDelinquent())) return;
        BigDecimal total = sub.getTotalBalance();
        if (total.signum() <= 0) return;
        boolean unpayablePaygDebt = isWorkflowCreditsOnlyPlan(sub)
                && sub.getPaygRemainingCredits().signum() < 0;
        if (!unpayablePaygDebt) {
            sub.setDelinquent(false);
            log.info("Cleared delinquent flag for subscription {} (totalBalance now {} = sub {} + payg {})",
                    sub.getId(), total, sub.getRemainingCredits(), sub.getPaygRemainingCredits());
        }
    }

    // ============================================================================
    // ===== V250+ Two-bucket balance model - sub_remaining + payg_remaining ======
    // ============================================================================
    //
    // V250 adds a 2nd-scalar `payg_remaining_credits` on the subscription row.
    // The PAYG bucket is fed by one-time Stripe `mode=PAYMENT` checkouts
    // (PR3 grantCredits sourceType="PAYG_TOPUP") and is consumed AFTER the sub
    // bucket on every debit. Subscription renewals reset `remaining_credits`
    // back to the plan's monthly quota but leave `payg_remaining_credits`
    // untouched - PAYG persists across billing cycles.
    //
    // Invariant V148 updated (extended for Free workflow-credit scoping):
    //   delinquent = TRUE  ⇒  (remaining_credits + payg_remaining_credits) ≤ 0
    //                          OR (FREE plan AND payg_remaining_credits < 0)
    // The payg-negative leg is only ever SET on FREE accounts (the debit that
    // creates it is the !subBucketEligible branch) and only BLOCKS the clear
    // on FREE accounts - paid plans keep the pure total-based lifecycle.
    //
    // {@link Subscription#getTotalBalance} returns the sum; getBalance + canAfford
    // + clearDelinquentIfPositive all key on the sum.
    //
    // Debit policy: drain sub first then payg. Refund policy: credit sub first
    // (the "primary" wallet - PAYG only fills via explicit grant).
    // ============================================================================

    /**
     * Result of splitting a debit amount across the two buckets.
     * {@code fromSub + fromPayg == totalDebit} (always).
     */
    private record BucketSplit(BigDecimal fromSub, BigDecimal fromPayg) {}

    /**
     * Compute how a positive {@code totalAmount} debit splits across the two
     * buckets. Policy:
     *
     * <ol>
     *   <li><b>No PAYG balance (paygAvail ≤ 0)</b> - all debit on sub. Preserves
     *       legacy overshoot semantics (pre-V250 callers expected
     *       {@code remaining_credits} to absorb chat post-flight overshoot;
     *       PAYG bucket only exists when a top-up grant happened).</li>
     *   <li><b>PAYG positive + sub positive</b> - drain sub first up to
     *       {@code subAvail}, then drain payg up to {@code paygAvail}, then
     *       any leftover overshoot lands back on sub (debt accumulates on
     *       the subscription bucket - PAYG never goes negative as a side-effect
     *       of overshoot).</li>
     *   <li><b>PAYG positive + sub at/below zero</b> - drain payg first
     *       (the user's cash), overshoot continues on sub. Sub renewal will
     *       later clear the sub deficit; PAYG cash should not absorb sub debt.</li>
     * </ol>
     *
     * <p>The invariant: PAYG balance only goes negative through an explicit
     * grantCredits with a negative amount (e.g. Stripe refund webhook -
     * not implemented in PR2). Normal debit/overshoot only ever pushes the
     * sub bucket negative.
     */
    private static BucketSplit splitBuckets(Subscription sub, BigDecimal totalAmount) {
        BigDecimal subAvail = sub.getRemainingCredits();
        BigDecimal paygAvail = sub.getPaygRemainingCredits();

        // Case 1: no PAYG balance - all debit on sub (legacy semantics)
        if (paygAvail.signum() <= 0) {
            return new BucketSplit(totalAmount, BigDecimal.ZERO);
        }

        // Case 2: PAYG positive, sub positive - drain sub first, then payg,
        // overshoot back to sub.
        if (subAvail.signum() > 0) {
            BigDecimal fromSub = subAvail.min(totalAmount);
            BigDecimal remaining = totalAmount.subtract(fromSub);
            BigDecimal fromPayg = paygAvail.min(remaining);
            BigDecimal extraOnSub = remaining.subtract(fromPayg);
            return new BucketSplit(fromSub.add(extraOnSub), fromPayg);
        }

        // Case 3: PAYG positive, sub at/below zero - drain payg, overshoot on sub
        BigDecimal fromPayg = paygAvail.min(totalAmount);
        BigDecimal fromSub = totalAmount.subtract(fromPayg);
        return new BucketSplit(fromSub, fromPayg);
    }

    /**
     * Apply a positive debit to the subscription, draining sub first then
     * payg via {@link #splitBuckets}. The {@code totalBalance} after the call
     * decreases by exactly {@code totalCost}. Caller is responsible for the
     * over-debit policy (delinquency flag, rejection audit row) - this helper
     * blindly subtracts (negative buckets are reachable via the allowNegative
     * branch).
     *
     * <p>Returns the {@link BucketSplit} so the caller can stamp
     * {@code paygPortion} on the reservation ledger row (V254). At commit /
     * release time that stored split is what makes the refund symmetric to
     * the debit - without it the refund would always credit the sub bucket
     * and silently destroy PAYG dollars at the next renewal.
     */
    private static BucketSplit applyDebit(Subscription sub, BigDecimal totalCost, boolean subEligible) {
        BucketSplit split = subEligible
                ? splitBuckets(sub, totalCost)
                : new BucketSplit(BigDecimal.ZERO, totalCost);
        sub.setRemainingCredits(sub.getRemainingCredits().subtract(split.fromSub()));
        sub.setPaygRemainingCredits(sub.getPaygRemainingCredits().subtract(split.fromPayg()));
        return split;
    }

    /**
     * V254 - symmetric refund: credit {@code paygPortion} back to the PAYG
     * bucket and the remainder to the sub bucket. Used by
     * {@link #releaseReservation} (full refund of the original reserve) and
     * by the under-shoot branch of {@link #commitReservation} (partial
     * refund of {@code reserved - actual}).
     *
     * <p>The legacy single-arg overload remains for grant / refund call sites
     * that have no notion of bucket-of-origin (e.g. credit-pack grants,
     * cross-tier refunds) - those keep the V250 "everything to sub" policy.
     */
    private static void applyRefund(Subscription sub, BigDecimal refundAmount, BigDecimal paygPortion) {
        BigDecimal payg = paygPortion == null ? BigDecimal.ZERO : paygPortion;
        // Clamp to refundAmount so a poisoned ledger row (paygPortion > total)
        // cannot conjure money on the PAYG side.
        if (payg.compareTo(refundAmount) > 0) payg = refundAmount;
        BigDecimal subPart = refundAmount.subtract(payg);
        sub.setRemainingCredits(sub.getRemainingCredits().add(subPart));
        if (payg.signum() > 0) {
            sub.setPaygRemainingCredits(sub.getPaygRemainingCredits().add(payg));
        }
    }

    /**
     * Filter for "we hit the unique constraint on source_id, treat as idempotent
     * success" vs "some other constraint failed, propagate". Postgres SQLState
     * 23505 = unique_violation. Constraint name match keeps us from masking
     * unrelated CHECK / FK violations.
     */
    private static boolean isDuplicateSourceId(DataIntegrityViolationException dive) {
        Throwable cause = dive.getMostSpecificCause();
        if (cause instanceof org.postgresql.util.PSQLException psql) {
            String state = psql.getSQLState();
            String msg = String.valueOf(psql.getServerErrorMessage());
            return "23505".equals(state) && msg != null && msg.toLowerCase().contains("source_id");
        }
        return false;
    }

    // ---- Result DTO ----

    public record CreditConsumeResult(boolean success, String error, BigDecimal creditsUsed, BigDecimal remainingCredits, boolean delinquent) {
        public static CreditConsumeResult success(BigDecimal cost, BigDecimal remaining) {
            return new CreditConsumeResult(true, null, cost, remaining, false);
        }
        public static CreditConsumeResult insufficientCredits(BigDecimal balance, BigDecimal required) {
            return new CreditConsumeResult(false, "Insufficient credits: balance=" + balance + ", required=" + required, BigDecimal.ZERO, balance, false);
        }
        public static CreditConsumeResult noSubscription() {
            return new CreditConsumeResult(false, "No active subscription", BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        /**
         * PR11 - returned by {@code consumeFor*} when the executor has hit
         * their per-member quota cap (configured by OWNER/ADMIN via
         * {@code OrganizationMemberQuotaLimit}). Distinct from
         * {@link #insufficientCredits} so the frontend can surface a
         * "ask your org admin to raise your cap" message instead of
         * "top up your wallet" - the wallet (owner's) MAY have plenty of
         * credits left.
         *
         * <p>The error string follows {@code QUOTA_CAP_EXCEEDED:<dim>:<consumed>/<cap>}
         * so consumers can parse the dimension and current utilisation
         * without RPC-back to the auth-service.
         */
        public static CreditConsumeResult quotaCapExceeded(String dimension,
                                                            java.math.BigDecimal consumed,
                                                            java.math.BigDecimal cap,
                                                            java.math.BigDecimal balance) {
            return new CreditConsumeResult(false,
                    "QUOTA_CAP_EXCEEDED:" + dimension + ":" + consumed + "/" + cap,
                    BigDecimal.ZERO, balance, false);
        }
        /**
         * Returned when a billable call references a (provider, model) pair
         * that has no pricing row in {@code auth.model_pricing}. Distinct
         * from {@code insufficientCredits} so the caller (and any human
         * looking at the failure) can tell "missing migration / catalog
         * drift" apart from "user is out of credits".
         */
        public static CreditConsumeResult noPricing(String provider, String model) {
            return new CreditConsumeResult(false, "No pricing configured for " + provider + "/" + model, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        /**
         * Returned by {@code tryReserveMarkup} when {@code subscription.delinquent=true}
         * and the caller's scope does not qualify for an in-flight bypass (chat
         * STREAM never bypasses; fresh RUN init refused). Distinct from
         * {@link #insufficientCredits} so the catalog can surface a "top up to
         * resume" message instead of generic "insufficient credits".
         */
        public static CreditConsumeResult delinquentRefused(BigDecimal balance) {
            return new CreditConsumeResult(false, "account delinquent - top up to resume", BigDecimal.ZERO, balance, true);
        }
    }
}
