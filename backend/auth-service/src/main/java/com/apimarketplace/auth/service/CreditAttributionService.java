package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Centralized service for credit attribution (grants, resets, pack upgrades).
 * Single responsibility: handles ONLY credit attribution logic.
 * CreditService remains the owner of consumption (deductions).
 *
 * Credit model:
 * - Plans unlock features only, they do NOT grant credits.
 *   Exception: FREE plan has includedLlmTokens (1000 credits). Admin-granted comp
 *   Starter/Pro/Team subscriptions ({@code provider="internal"}, non-FREE plan) grant the
 *   tier-0 base pack (5000 credits) - the same base a paying customer with no pack receives.
 * - Credits come from credit packs (tiers) via Stripe slider.
 * - Credit tier upgrades use billing_cycle_anchor:NOW (new cycle, full grant).
 * - Credit tier downgrades are scheduled (end of period).
 * - Plan changes (upgrade/downgrade) have NO credit logic.
 *
 * Idempotence strategy (subscription-based sourceId):
 * 1. BillingEvent.existsByEventId() - rejects duplicate webhooks (caller responsibility)
 * 2. CreditLedgerRepository.existsBySourceId() - exact match prevents double-grant
 *    (backed by UNIQUE partial index on source_id, V6 migration)
 * 3. SourceId derived from subscription state (subscriptionId + currentPeriodStart),
 *    making idempotency structural - same subscription state always = same sourceId.
 *
 * SourceId formats:
 * - Initial plan credits:  plan_sub_{subId}_init
 * - Initial pack credits:  pack_sub_{subId}_init
 * - Renewal reset:         reset_sub_{subId}_{epochSec}
 * - Renewal plan/pack:     plan_sub_{subId}_{epochSec} / pack_sub_{subId}_{epochSec}
 * - Pack upgrade:          pack_sub_{subId}_upgrade_{epochSec}
 */
@Service
public class CreditAttributionService {

    private static final Logger log = LoggerFactory.getLogger(CreditAttributionService.class);

    private final CreditService creditService;
    private final CreditLedgerRepository ledgerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    public CreditAttributionService(CreditService creditService,
                                     CreditLedgerRepository ledgerRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     PendingCreditUpgradeRepository pendingCreditUpgradeRepository) {
        this.creditService = creditService;
        this.ledgerRepository = ledgerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.pendingCreditUpgradeRepository = pendingCreditUpgradeRepository;
    }

    /**
     * Attribute credits on a new subscription creation.
     * Grants full credit pack credits or plan-included credits.
     *
     * @param userId          the user ID
     * @param subscription    the local subscription entity (used for sourceId derivation)
     * @param creditQuantity  Stripe quantity for credit pack (0 = no pack)
     */
    @Transactional
    public void attributeOnSubscription(Long userId, Subscription subscription, int creditQuantity) {
        try {
            Plan plan = subscription.getPlan();
            String subKey = "sub_" + subscription.getId();
            log.info("Attributing credits for new subscription: userId={}, plan={}, creditQty={}, subId={}",
                    userId, plan.getCode(), creditQuantity, subscription.getId());

            if (grantsBasePack(subscription, creditQuantity)) {
                // Paid plans AND admin-granted comp plans (internal, non-FREE): grant the
                // tier-0 base pack (5K at $0 when creditQuantity=0). A comp Starter/Pro/Team
                // therefore gets the same 5K base as a paying customer with no pack - never
                // the plan's larger allowance (admin-credits "5k/month max" rule).
                grantPackCredits(userId, "pack_" + subKey + "_init", creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Internal FREE plan: grant plan-included credits (1K)
                grantPlanCredits(userId, "plan_" + subKey + "_init", plan);
            }
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate credit attribution detected for subId={}, treating as idempotent skip",
                    subscription.getId());
        }
    }

    /**
     * Attribute credits on subscription renewal (invoice.paid with billing_reason=subscription_cycle).
     * Resets balance to zero, then re-grants full pack credits.
     *
     * @param userId       the user ID
     * @param subscription the local subscription entity
     */
    @Transactional
    public void attributeOnRenewal(Long userId, Subscription subscription) {
        try {
            Plan plan = subscription.getPlan();
            if (plan == null) {
                log.error("Cannot attribute renewal credits: subscription {} has no plan. userId={}",
                        subscription.getId(), userId);
                return;
            }

            int creditQuantity = subscription.getCreditQuantity() != null ? subscription.getCreditQuantity() : 0;
            String subKey = "sub_" + subscription.getId();
            String periodSuffix = periodKey(subscription.getCurrentPeriodStart());

            log.info("Attributing credits for renewal: userId={}, plan={}, creditQty={}, subId={}, period={}",
                    userId, plan.getCode(), creditQuantity, subscription.getId(), periodSuffix);

            // Reset balance to zero
            resetBalance(userId, "reset_" + subKey + "_" + periodSuffix, subscription);

            // Re-grant credits
            if (grantsBasePack(subscription, creditQuantity)) {
                // Paid plans AND admin-granted comp plans (internal, non-FREE): grant the
                // tier-0 base pack (5K at $0 when creditQuantity=0). Keeps a comp Starter/Pro/Team
                // renewing at the 5K base every cycle - never the plan's larger allowance.
                grantPackCredits(userId, "pack_" + subKey + "_" + periodSuffix, creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Internal FREE plan: grant plan-included credits (1K)
                grantPlanCredits(userId, "plan_" + subKey + "_" + periodSuffix, plan);
            }
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate renewal credit attribution detected for subId={}, treating as idempotent skip",
                    subscription.getId());
        }
    }

    /**
     * Handle credit pack tier upgrade.
     * With billing_cycle_anchor:NOW, Stripe starts a new cycle and charges full price.
     * We grant the full new pack credits. User keeps their remaining balance.
     *
     * @param userId             the user ID
     * @param subscription       the local subscription entity
     * @param oldCreditQuantity  previous Stripe quantity (kept for logging)
     * @param newCreditQuantity  new Stripe quantity (tier cost)
     */
    @Transactional
    public void handleCreditPackChange(Long userId, Subscription subscription,
                                        int oldCreditQuantity, int newCreditQuantity) {
        try {
            String planCode = subscription.getPlan().getCode();
            String subKey = "sub_" + subscription.getId();
            String periodSuffix = periodKey(subscription.getCurrentPeriodStart());

            log.info("Credit pack upgrade for userId={}: qty {} -> {}, plan={}, subId={}",
                    userId, oldCreditQuantity, newCreditQuantity, planCode, subscription.getId());

            if (newCreditQuantity <= 0) {
                log.info("Pack removed for userId={}, no credits to grant", userId);
                return;
            }

            // Grant full new pack credits (no reset - user keeps remaining balance)
            grantPackCredits(userId, "pack_" + subKey + "_upgrade_" + periodSuffix, newCreditQuantity, planCode);
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate pack change credit attribution detected for subId={}, treating as idempotent skip",
                    subscription.getId());
        }
    }

    /**
     * Grant credits for a credit-tier upgrade that has been paid via the Option A
     * one-shot invoice flow. Called from the {@code invoice.paid} webhook handler
     * after looking up a {@link PendingCreditUpgrade} row by Stripe invoice id.
     *
     * <p>Source-id format ({@code stripe_invoice:<id>}) is UNIQUE across the ledger
     * (V6 partial index), so duplicate webhook deliveries - or a race with a future
     * synchronous grant path - collapse to a single row via the
     * {@code DataIntegrityViolationException} swallow below.
     *
     * <p>This path is the sole place that grants pack credits for an Option A
     * upgrade. The legacy {@code handleCreditPackChange} (triggered by
     * {@code customer.subscription.updated}) is skipped by
     * {@code SubscriptionService.onSubscriptionUpsert} when a matching pending row
     * exists, preventing double-grants.
     *
     * @param pending the pending-upgrade row resolved from the webhook's invoice id
     */
    @Transactional
    public void handleCreditUpgradeInvoicePaid(PendingCreditUpgrade pending) {
        if (pending == null) {
            log.warn("handleCreditUpgradeInvoicePaid called with null pending - skipping");
            return;
        }
        if (PendingCreditUpgrade.STATUS_FAILED.equals(pending.getStatus())) {
            log.error("invoice.paid received for a FAILED pending upgrade - refusing to grant. " +
                    "invoice={}, user={}, sub={}",
                    pending.getStripeInvoiceId(), pending.getUserId(), pending.getProviderSubscriptionId());
            return;
        }
        try {
            Subscription subscription = subscriptionRepository.findById(pending.getSubscriptionId())
                    .orElse(null);
            if (subscription == null) {
                log.error("handleCreditUpgradeInvoicePaid: subscription {} not found for pending invoice {}",
                        pending.getSubscriptionId(), pending.getStripeInvoiceId());
                return;
            }
            Plan plan = subscription.getPlan();
            String planCode = plan != null ? plan.getCode() : "UNKNOWN";

            String sourceId = "stripe_invoice:" + pending.getStripeInvoiceId();
            log.info("Granting credit-upgrade pack credits: user={}, sub={}, tier={}, qty={}, invoice={}",
                    pending.getUserId(), subscription.getId(), pending.getTargetTierIndex(),
                    pending.getTargetCreditQuantity(), pending.getStripeInvoiceId());

            grantPackCredits(pending.getUserId(), sourceId, pending.getTargetCreditQuantity(), planCode);
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate credit-upgrade grant for invoice={} - V6 UNIQUE absorbs, treating as idempotent skip",
                    pending.getStripeInvoiceId());
        }
    }

    // ========== Private helpers ==========

    /**
     * Plan codes that an admin can grant as a complimentary subscription. When such a plan
     * is held on an {@code internal} (non-Stripe) subscription it grants the tier-0 5K base -
     * the same as a paying customer with no pack. Mirrors
     * {@code AdminPlanService.ALLOWED_PLAN_CODES} minus FREE (FREE keeps its 1K plan grant).
     */
    private static final java.util.Set<String> COMP_BASE_PACK_PLANS = java.util.Set.of("STARTER", "PRO", "TEAM");

    /**
     * Decide whether a subscription grants the tier-0 <b>base pack</b> (5,000 credits
     * at $0 when {@code creditQuantity == 0}) versus the smaller plan-included grant.
     *
     * <p>True for:
     * <ul>
     *   <li>Any paid subscription ({@code provider != "internal"}) - unchanged behaviour
     *       (paid Starter/Pro/Team/Enterprise all flow here, exactly as before).</li>
     *   <li>Any subscription that carries an explicit credit pack ({@code creditQuantity > 0}).</li>
     *   <li>An admin-granted comp Starter/Pro/Team row that is still {@code provider == "internal"}.
     *       This is the ONLY new case - it makes a comp plan receive the same 5K base as a
     *       paying customer with no pack (the admin-credits "5k/month max" rule).</li>
     * </ul>
     *
     * <p>Deliberately narrow on the third branch: internal FREE keeps its 1K plan-included
     * grant, and internal CREDIT_PACK/PAYG subs keep granting nothing at qty 0. Only the
     * previously-impossible "internal + Starter/Pro/Team" case changes behaviour, so the
     * mapping is provably a no-op for every pre-existing subscription shape.
     */
    private static boolean grantsBasePack(Subscription subscription, int creditQuantity) {
        boolean isPaidSubscription = !"internal".equalsIgnoreCase(subscription.getProvider());
        if (isPaidSubscription || creditQuantity > 0) {
            return true;
        }
        String code = subscription.getPlan() != null ? subscription.getPlan().getCode() : null;
        return code != null && COMP_BASE_PACK_PLANS.contains(code.toUpperCase());
    }

    /**
     * Convert a period start timestamp to epoch seconds string for sourceId construction.
     */
    private static String periodKey(LocalDateTime periodStart) {
        if (periodStart == null) {
            return "0";
        }
        return String.valueOf(periodStart.toEpochSecond(ZoneOffset.UTC));
    }

    /**
     * V250/PR3 - Grant credits from a Stripe PAYG one-time top-up checkout.
     *
     * <p>Called from {@code WebhookController.handleCheckoutCompleted} when
     * the metadata.kind == "payg_topup" branch matches. Routes the grant
     * via {@code CreditService.grantCredits(sourceType="PAYG_TOPUP")} which
     * lands the amount on {@code subscription.payg_remaining_credits}
     * (V250 bucket - persists across sub-renewal cycles).
     *
     * <p>Idempotent: the underlying {@code grantCredits} catches
     * {@code DataIntegrityViolationException} via the unique constraint on
     * {@code credit_ledger.source_id}. Caller passes the Stripe session id
     * as sourceId - Stripe replays the same session id, the second grant
     * is a no-op skip.
     *
     * @param userId    recipient user
     * @param amount    credit amount (NOT cents - already in credit units)
     * @param sessionId Stripe checkout session id (used as ledger sourceId
     *                  for idempotence)
     * @param tier      "small" / "medium" / "large" - used only for audit
     *                  description and Prometheus labels (optional)
     */
    public void grantPaygTopup(Long userId, BigDecimal amount, String sessionId, String tier) {
        if (ledgerRepository.existsBySourceId(sessionId)) {
            log.info("PAYG top-up already granted for sessionId={}, idempotent skip", sessionId);
            return;
        }

        String description = "PAYG top-up tier=" + (tier == null ? "?" : tier);
        log.info("Granting {} PAYG credits (tier={}) to userId={} sessionId={}",
                amount, tier, userId, sessionId);

        CreditConsumeResult result = creditService.grantCredits(
                userId, amount, "PAYG_TOPUP", sessionId, description);
        if (!result.success()) {
            log.error("Failed to grant PAYG top-up for userId={}, tier={}, sessionId={}: {}",
                    userId, tier, sessionId, result.error());
            throw new IllegalStateException("PAYG top-up grant failed: " + result.error());
        }
    }

    /**
     * Grant plan-included credits for plans that have includedLlmTokens (e.g. FREE plan).
     * Used when no credit pack is attached (creditQuantity = 0).
     */
    private void grantPlanCredits(Long userId, String sourceId, Plan plan) {
        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Plan credits already granted for sourceId={}, skipping", sourceId);
            return;
        }

        BigDecimal amount = BigDecimal.valueOf(plan.getIncludedLlmTokens());
        log.info("Granting {} plan-included credits (plan={}) to userId={}",
                plan.getIncludedLlmTokens(), plan.getCode(), userId);

        CreditConsumeResult result = creditService.grantCredits(userId, amount, "PURCHASE", sourceId,
                "Plan-included credits: " + plan.getCode() + " (" + plan.getIncludedLlmTokens() + " credits)");
        if (!result.success()) {
            log.error("Failed to grant plan credits for userId={}, plan={}: {}",
                    userId, plan.getCode(), result.error());
            throw new IllegalStateException("Plan credit grant failed: " + result.error());
        }
    }

    /**
     * Grant credit pack credits (PURCHASE) based on the Stripe quantity (tier cost).
     * Uses CreditTierConstants to resolve tier index -> credit amount.
     */
    private void grantPackCredits(Long userId, String sourceId, int creditQuantity, String planCode) {
        if (creditQuantity < 0) {
            return;
        }

        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Pack credits already granted for sourceId={}, skipping", sourceId);
            return;
        }

        int tierIndex = CreditTierConstants.resolveTierIndex(creditQuantity, planCode);
        int creditAmount = CreditTierConstants.getCreditAmount(tierIndex);

        // Validate tier for plan (e.g. Starter max tier)
        try {
            CreditTierConstants.validateTierForPlan(tierIndex, planCode);
        } catch (IllegalArgumentException e) {
            log.warn("Credit pack tier {} not valid for plan {}: {}. Granting anyway (Stripe already charged).",
                    tierIndex, planCode, e.getMessage());
        }

        BigDecimal amount = BigDecimal.valueOf(creditAmount);
        log.info("Granting {} pack credits (tier={}, cost={}) to userId={}",
                creditAmount, tierIndex, creditQuantity, userId);

        CreditConsumeResult result = creditService.grantCredits(userId, amount, "PURCHASE", sourceId,
                "Credit pack: tier " + tierIndex + " (" + creditAmount + " credits)");
        if (!result.success()) {
            log.error("Failed to grant pack credits for userId={}, tier={}: {}",
                    userId, tierIndex, result.error());
            throw new IllegalStateException("Credit pack grant failed: " + result.error());
        }
    }

    /**
     * Reset balance to zero before renewal re-grant.
     * Creates a PLAN_RESET ledger entry for audit trail.
     */
    private void resetBalance(Long userId, String sourceId, Subscription subscription) {
        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Balance already reset for sourceId={}, skipping", sourceId);
            return;
        }

        BigDecimal currentBalance = subscription.getRemainingCredits() != null ? subscription.getRemainingCredits() : BigDecimal.ZERO;
        if (currentBalance.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Balance already zero for userId={}, no reset needed", userId);
            return;
        }

        // Set balance to zero
        subscription.setRemainingCredits(BigDecimal.ZERO);
        subscriptionRepository.save(subscription);

        // Create ledger entry for audit trail
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setUserId(userId);
        entry.setAmount(currentBalance.negate()); // negative of current balance to bring to zero
        entry.setBalanceAfter(BigDecimal.ZERO);
        entry.setSourceType("PLAN_RESET");
        entry.setSourceId(sourceId);
        entry.setDescription("Balance reset on renewal (previous balance: " + currentBalance + ")");
        ledgerRepository.save(entry);

        log.info("Balance reset for userId={}: {} -> 0 (sourceId={})", userId, currentBalance, sourceId);
    }

}
