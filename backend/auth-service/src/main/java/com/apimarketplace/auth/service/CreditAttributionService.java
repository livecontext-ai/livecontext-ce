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
import java.util.Optional;

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
 * - Monthly credit cycle:  reset_sub_{subId}_{epochSec} / pack_sub_{subId}_{epochSec}, where the
 *                          epoch is the CYCLE start (currentPeriodStart + N months), so a yearly
 *                          subscription's eleven intra-year grants never collide with its
 *                          period-start grant nor with each other (see
 *                          {@link #attributeMonthlyCreditCycle}).
 */
@Service
public class CreditAttributionService {

    /**
     * Product-analytics emitter (PostHog). Optional so hand-built test instances and
     * analytics-less deployments are untouched; a null field emits nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    private static final Logger log = LoggerFactory.getLogger(CreditAttributionService.class);

    private final CreditService creditService;
    private final CreditLedgerRepository ledgerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;

    /**
     * Needed for {@code refresh}: a {@code SELECT ... FOR UPDATE} through Spring Data returns the
     * first-level-cached instance when the entity is already managed here, so the lock is taken
     * but the values are whatever the caller loaded. See {@link #resolveManagedForUpdate}.
     * Optional so the existing 4-arg constructor keeps working in unit tests.
     */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

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
     * <p>No {@link #resolveManagedForUpdate} here, unlike the renewal and pack-change paths:
     * every caller passes a subscription that is MANAGED in its own transaction (freshly
     * persisted, or loaded inside it), and the sourceId is keyed on {@code _init} rather than
     * on a mutable period - there is no stale value to read.
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
                String planSourceId = "plan_" + subKey + "_init";
                // V494: the AI allowance rides with this grant, and is seeded ONLY on the
                // first one. Two properties depend on that, and both are load-bearing:
                //
                //  - It inherits the EMAIL-VERIFICATION gate. This method is only reached
                //    for a FREE row through attributeCreditsIfEligible, which returns early
                //    for an unverified user. Seeding the pot where the row is CREATED (which
                //    is what this did first) handed 100 credits of platform-key inference to
                //    every unverified throwaway signup, scriptably, while the 1000 monthly
                //    credits sitting beside it stayed correctly withheld.
                //  - It is IDEMPOTENT. This runs on EVERY login, so a refill that did not
                //    key off the ledger row would top the pot back up to full on each one:
                //    an unlimited allowance, refilled by reloading the page.
                //    Taken from the grant's own return rather than a second existence
                //    query, which is the same signal one round trip cheaper.
                if (grantPlanCredits(userId, planSourceId, plan)) {
                    refillAiAllowance(subscription, plan);
                }
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
    public RenewalOutcome attributeOnRenewal(Long userId, Subscription subscription) {
        return attributeOnRenewal(userId, subscription, null);
    }

    /**
     * What {@link #attributeOnRenewal(Long, Subscription, LocalDateTime)} actually did, so the
     * caller can log the truth instead of assuming success. The internal scheduler used to
     * announce "renewed" unconditionally, which is precisely how a renewal that granted nothing
     * stayed invisible in production for weeks.
     */
    public enum RenewalOutcome {
        /** Period advanced (when asked) and the cycle's credits were attributed. */
        RENEWED,
        /** Another actor had already renewed this cycle; deliberately left untouched. */
        ALREADY_RENEWED,
        /** The row could not be renewed (not resolvable, no longer eligible, no plan). */
        SKIPPED
    }

    /**
     * Renewal attribution that can also ADVANCE the billing period, atomically with
     * the reset + re-grant.
     *
     * <p>{@code newPeriodStart} is the contract difference between the two renewal
     * callers:
     * <ul>
     *   <li><b>{@code null}</b> - the caller already owns the period (Stripe
     *       {@code invoice.paid}, where Stripe is the source of truth for the cycle,
     *       and {@code AdminPlanService.assignPlan}, which anchors the cycle itself
     *       before calling). The period is left alone. One thing DID change for these
     *       callers: the row is now re-read under lock, so the plan, the credit quantity,
     *       the period and the balance are the live values rather than whatever the caller
     *       loaded. For the Stripe webhook that window is small (it loads the row a few lines
     *       before calling), so this is defence rather than a bug fix there - but the reload
     *       is what makes {@code resetBalance}'s write safe for ANY caller, which is the
     *       point, and it can now return {@link RenewalOutcome#SKIPPED} where the row has
     *       vanished instead of writing through a detached copy.</li>
     *   <li><b>non-null</b> - the internal monthly scheduler, which has no external
     *       source of truth for the cycle. The period is advanced HERE, inside this
     *       transaction and BEFORE the sourceId is derived from it.</li>
     * </ul>
     *
     * <p>Doing the advance here (instead of in the scheduler, after this call) fixes
     * two production defects that between them made every internal renewal wrong:
     * <ol>
     *   <li><b>Lost update.</b> The scheduler used to write the subscription row again
     *       AFTER this method committed. Its {@link Subscription} is detached (a
     *       {@code @Scheduled} thread has no persistence context), and the grant lands
     *       on a re-resolved managed instance, so that trailing {@code save} merged a
     *       pre-grant copy back over the fresh balance. Silent: the ledger showed the
     *       grant, the wallet showed zero.</li>
     *   <li><b>Period-key reuse.</b> The sourceId embeds {@code currentPeriodStart}.
     *       Attributing BEFORE advancing it re-mints the previous cycle's key, which
     *       the {@code existsBySourceId} guards then skip - the renewal silently
     *       granted nothing at all.</li>
     * </ol>
     * Advancing first also makes the pair crash-safe: period and credits commit
     * together, so a failure leaves the subscription expired and the next hourly pass
     * retries it cleanly.
     *
     * @return what actually happened, so a caller can log the truth rather than assume success
     */
    @Transactional
    public RenewalOutcome attributeOnRenewal(Long userId, Subscription subscription, LocalDateTime newPeriodStart) {
        try {
            // Work on a MANAGED instance holding the row's live values; the caller's object
            // may be detached and stale (see resolveManagedForUpdate).
            // No fall-back to the caller's instance, for EITHER overload. This method always
            // writes through the entity it works on - resetBalance zeroes the balance and saves
            // it - so continuing on a detached copy would merge every stale column back over the
            // row (defect 3), and if the row is genuinely gone the merge would re-insert it as a
            // brand-new subscription. There is nothing safe to do without the live row.
            Subscription sub = resolveManagedForUpdate(subscription).orElse(null);
            if (sub == null) {
                log.error("Cannot attribute renewal credits: subscription row {} is not resolvable for update. userId={}",
                        subscription == null ? null : subscription.getId(), userId);
                return RenewalOutcome.SKIPPED;
            }

            if (newPeriodStart != null) {
                // Re-validate UNDER the lock everything the unlocked selection query matched on.
                // Between that READ COMMITTED select and acquiring this lock, another actor may
                // have renewed the row (concurrent admin comp grant, or an overlapping pass once
                // one exceeds lockAtMostFor) or made it ineligible (a Stripe upgrade cancels the
                // internal sibling). Acting on a stale match grants the same cycle twice.
                // NOTE what this does NOT protect: grantCredits resolves the wallet by user
                // (most-recent active row), so on an account that holds both an active internal
                // row and a newer active Stripe row, the reset and the grant still land on
                // different rows. Sibling-cancellation in SubscriptionService is what keeps that
                // shape from existing; this guard only stops us acting on a row that already
                // moved on.
                if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(newPeriodStart)) {
                    log.info("Subscription {} was already renewed by a concurrent actor (period ends {}), skipping",
                            sub.getId(), sub.getCurrentPeriodEnd());
                    return RenewalOutcome.ALREADY_RENEWED;
                }
                if (!isInternalRenewalEligible(sub)) {
                    log.info("Subscription {} is no longer eligible for internal renewal (provider={}, status={}), skipping",
                            sub.getId(), sub.getProvider(), sub.getStatus());
                    return RenewalOutcome.SKIPPED;
                }
                // Advance BEFORE the plan check: a row we refuse to attribute must still
                // leave the expired window, otherwise every hourly pass re-picks it forever.
                sub.setCurrentPeriodStart(newPeriodStart);
                sub.setCurrentPeriodEnd(newPeriodStart.plusMonths(1));
                // updatedAt is deliberately NOT set here - @PreUpdate stamps it at flush.
            }

            Plan plan = sub.getPlan();
            if (plan == null) {
                log.error("Cannot attribute renewal credits: subscription {} has no plan. userId={}",
                        sub.getId(), userId);
                return RenewalOutcome.SKIPPED;
            }

            int creditQuantity = sub.getCreditQuantity() != null ? sub.getCreditQuantity() : 0;
            String subKey = "sub_" + sub.getId();
            String periodSuffix = periodKey(sub.getCurrentPeriodStart());

            log.info("Attributing credits for renewal: userId={}, plan={}, creditQty={}, subId={}, period={}",
                    userId, plan.getCode(), creditQuantity, sub.getId(), periodSuffix);

            // Reset balance to zero
            String resetSourceId = "reset_" + subKey + "_" + periodSuffix;
            // Every credit write below is keyed on a sourceId and therefore idempotent on
            // its own; the allowance refill is a straight SET with no ledger row of its
            // own, so it needs the marker above or a second call for the same period tops
            // the pot back up. Unreachable on the scheduler path (it advances the period
            // first, so the key is always fresh) and on a repeated
            // AdminPlanService.assignPlan (which re-anchors currentPeriodStart, and
            // periodKey is epoch SECONDS, so it mints a fresh key). The path it does
            // protect is a redelivered Stripe invoice.paid: WebhookController calls
            // attributeOnRenewal with newPeriodStart = null, so the period - and therefore
            // the key - is identical across deliveries. No paid plan carries an allowance
            // today, but V494 advertises it as live-tunable, so the first one that does
            // must not hand out a free pot per redelivery.
            if (!ledgerRepository.existsBySourceId(resetSourceId)) {
                resetBalance(userId, resetSourceId, sub);
            } else {
                log.info("Balance already reset for sourceId={}, skipping", resetSourceId);
            }

            // Re-grant credits. The return value is ALSO the answer to "has this period
            // already been attributed?", which the allowance refill below needs: the
            // refill is a straight SET with no ledger row of its own, so without a marker
            // a second call for the same period tops the pot back up. Taken from the
            // grant rather than from the reset row, because resetBalance returns before
            // writing whenever the sub bucket is already zero - the ordinary state of an
            // account that spent its month, i.e. exactly the accounts being renewed. And
            // taken from the CALL rather than from a second existsBySourceId, because
            // this is the hourly sweep's hot path and the grant already asked.
            boolean attributedNow;
            if (grantsBasePack(sub, creditQuantity) && creditQuantity < 0) {
                // Nothing to grant, which is NOT "already attributed": the allowance still
                // has to be brought in line with the plan (cleared, on a plan that grants
                // none). grantPackCredits returns false for this case AND for the
                // already-granted one, so the two are separated here rather than there.
                //
                // Scoped to the base-pack branch on purpose: a negative quantity on an
                // INTERNAL FREE row must still fall through to its plan-included grant,
                // which is what it did before this flag existed. Short-circuiting ahead of
                // the branch selection quietly stopped those rows renewing at all.
                attributedNow = true;
            } else if (grantsBasePack(sub, creditQuantity)) {
                // Paid plans AND admin-granted comp plans (internal, non-FREE): grant the
                // tier-0 base pack (5K at $0 when creditQuantity=0). Keeps a comp Starter/Pro/Team
                // renewing at the 5K base every cycle - never the plan's larger allowance.
                attributedNow = grantPackCredits(userId, "pack_" + subKey + "_" + periodSuffix,
                        creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Internal FREE plan: grant plan-included credits (1K)
                attributedNow = grantPlanCredits(userId, "plan_" + subKey + "_" + periodSuffix, plan);
            } else {
                // A plan that grants no credits at all writes NO row to key on - and the
                // reset row is not a substitute, because resetBalance skips it whenever the
                // balance is already zero (see its javadoc), so keying on it would re-arm
                // the guard on every call. The only refill such a plan can need is the
                // CLEAR (it has no allowance to grant), and clearing an already-clear pot
                // is idempotent by construction, so it is safe to run unguarded. A plan
                // that granted no credits but DID carry an allowance is the one shape this
                // leaves un-refilled; it has no marker, and inventing one for a
                // combination nothing creates would be guessing.
                boolean clearsOnly = plan.getIncludedAiCredits() == null || plan.getIncludedAiCredits() <= 0;
                attributedNow = clearsOnly;
            }

            // V494: refill the separate AI allowance to the plan's configured amount.
            // A straight SET, not an add: the pot is a monthly allowance, so an unused
            // remainder does not roll over and a mid-cycle change of the plan's amount
            // takes effect at the next renewal. Plans with no allowance (every paid one)
            // have the pot CLEARED, which is what stops a leftover pot from following an
            // account onto a plan that does not grant one. An account whose e-mail is not
            // verified is treated exactly like a plan with no allowance: the pot buys real
            // inference, and this leg is the one the creation-path gate does not cover.
            if (attributedNow) {
                refillAiAllowance(sub, plan);
            }
            return RenewalOutcome.RENEWED;
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate renewal credit attribution detected for subId={}, treating as idempotent skip",
                    subscription == null ? null : subscription.getId());
            return RenewalOutcome.SKIPPED;
        }
    }

    /**
     * What {@link #attributeMonthlyCreditCycle} actually did, so the scheduler logs the truth.
     */
    public enum MonthlyCycleOutcome {
        /** The next monthly cycle was due; balance reset and pack re-granted. */
        GRANTED,
        /**
         * The cycle was due by the index but its ledger keys already existed, so the guards
         * wrote nothing and only the index moved. The second line of defence doing its job (a
         * rewound index, a replay); reported as its own outcome so the caller never announces
         * a grant that did not happen.
         */
        ABSORBED,
        /** The current cycle is already granted; nothing to do until the next month boundary. */
        NOT_DUE,
        /** Not eligible (row gone, not a yearly Stripe subscription, not active, no plan). */
        SKIPPED
    }

    /**
     * Grant the next MONTHLY credit cycle of a YEARLY Stripe subscription (V498).
     *
     * <p><b>Why this exists.</b> The credit pack is priced per unit per month on every cadence
     * ($1.00 monthly, $12.00 yearly: twelve months at the monthly rate, no discount) and sold as
     * "credits per month". A monthly subscription is re-granted on every Stripe
     * {@code invoice.paid} ({@code subscription_cycle}); a yearly one raises that invoice once
     * every twelve months and was therefore granted ONE month of credits for a year of payment.
     * This method is the eleven intra-year grants that were missing. It does exactly what
     * {@link #attributeOnRenewal} does (reset, then re-grant the pack at the row's live
     * quantity) so a yearly customer gets, month after month, what a monthly one gets.
     *
     * <p><b>The cycle is anchored on the billing period, not chained.</b> Cycle {@code N}
     * starts at {@code currentPeriodStart.plusMonths(N)}; {@code Subscription.creditCycleIndex}
     * records the last one granted. Chaining {@code plusMonths(1)} from the previous cycle
     * would drift on a day-29..31 anchor (Jan 31 to Feb 28 to Mar 28 ...) and end up granting a
     * thirteenth pack a few days before the yearly renewal. The anchor is also why the index is
     * reset by {@code Subscription.setCurrentPeriodStart}: a new billing period restarts the
     * count. Cycle 0 is the period-start grant itself ({@code _init} or the yearly
     * {@code invoice.paid}), never this method: this method cannot backfill a cycle 0 the
     * renewal failed to deliver. Known, pre-existing and shared with monthly rows: the
     * {@code invoice.paid} handler keys the renewal on the LOCAL {@code currentPeriodStart},
     * so when it lands before the {@code customer.subscription.updated} that moves the period,
     * the grant is keyed on the previous anchor, and if that key was already consumed the
     * renewal grants nothing. A cycle that would start at or after {@code currentPeriodEnd}
     * belongs to the next billing period and is left to Stripe.
     *
     * <p><b>Catch-up grants once, not once per missed month.</b> Several cycles can be due at
     * once (a yearly subscription that predates the scheduler, or a long outage). The index
     * jumps to the latest due cycle and that one is granted; intermediate cycles are not
     * replayed, because each would have been reset by the next anyway ("credits do not roll
     * over"), and replaying them would only write reset/grant pairs that cancel out.
     *
     * <p>Same locking, idempotence and write discipline as the renewal path: the row is
     * re-read under {@code PESSIMISTIC_WRITE} and re-validated, the index moves inside this
     * transaction BEFORE the grant so a crash retries cleanly and a concurrent pass sees the
     * cycle taken, and the sourceIds are keyed on the cycle start so a replay is absorbed by
     * {@code existsBySourceId}. The PAYG bucket is untouched, as on every renewal.
     *
     * @param now the instant the scheduler is evaluating against (one value for the whole pass)
     * @return what actually happened, so the caller can log the truth rather than assume a grant
     */
    @Transactional
    public MonthlyCycleOutcome attributeMonthlyCreditCycle(Long userId, Subscription subscription, LocalDateTime now) {
        try {
            Subscription sub = resolveManagedForUpdate(subscription).orElse(null);
            if (sub == null) {
                log.error("Cannot attribute monthly credit cycle: subscription row {} is not resolvable for update. userId={}",
                        subscription == null ? null : subscription.getId(), userId);
                return MonthlyCycleOutcome.SKIPPED;
            }
            // Re-validate UNDER the lock what the unlocked selection matched on: a cycle change
            // to monthly, a cancellation or a plan swap may have landed since.
            if (!isMonthlyCreditCycleEligible(sub)) {
                log.info("Subscription {} is not eligible for a monthly credit cycle (provider={}, cadence={}, status={}), skipping",
                        sub.getId(), sub.getProvider(), sub.getCadence(), sub.getStatus());
                return MonthlyCycleOutcome.SKIPPED;
            }
            Plan plan = sub.getPlan();
            if (plan == null) {
                log.error("Cannot attribute monthly credit cycle: subscription {} has no plan. userId={}",
                        sub.getId(), userId);
                return MonthlyCycleOutcome.SKIPPED;
            }

            int granted = sub.getCreditCycleIndex();
            int due = dueCreditCycle(sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(), now);
            if (due <= granted) {
                return MonthlyCycleOutcome.NOT_DUE;
            }

            LocalDateTime cycleStart = sub.getCurrentPeriodStart().plusMonths(due);
            int creditQuantity = sub.getCreditQuantity() != null ? sub.getCreditQuantity() : 0;
            String subKey = "sub_" + sub.getId();
            String cycleSuffix = periodKey(cycleStart);

            log.info("Attributing monthly credit cycle {} (of a yearly period) for userId={}, plan={}, creditQty={}, subId={}, cycleStart={}",
                    due, userId, plan.getCode(), creditQuantity, sub.getId(), cycleStart);

            // Advance BEFORE granting, inside this transaction: a failed grant rolls the index
            // back with it, a committed one can never be re-granted by the next pass.
            sub.setCreditCycleIndex(due);

            // The idempotence guard lives HERE, not inside resetBalance: V494 moved it out to
            // the callers, because resetBalance returns before writing whenever the sub bucket
            // is already zero, so its ledger row is not a reliable "already done" marker. This
            // cycle CAN be replayed (an index rewound by hand, or by the documented upsert
            // race), and without the guard the replay would zero a wallet the grant then
            // refuses to refill - its own key is already spent. Pinned by
            // ledgerKeyGuardHoldsWhenTheIndexIsRewound on a real database.
            String resetSourceId = "reset_" + subKey + "_" + cycleSuffix;
            if (!ledgerRepository.existsBySourceId(resetSourceId)) {
                resetBalance(userId, resetSourceId, sub);
            } else {
                log.info("Balance already reset for sourceId={}, skipping", resetSourceId);
            }

            boolean wrote;
            if (grantsBasePack(sub, creditQuantity)) {
                wrote = grantPackCredits(userId, "pack_" + subKey + "_" + cycleSuffix, creditQuantity, plan.getCode());
            } else if (plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0) {
                // Unreachable for a Stripe row today (grantsBasePack is true for every paid
                // subscription); kept symmetrical with attributeOnRenewal on purpose.
                wrote = grantPlanCredits(userId, "plan_" + subKey + "_" + cycleSuffix, plan);
            } else {
                wrote = false;
            }
            // V494: the AI allowance is a MONTHLY pot, so a yearly subscription must have it
            // refilled every month for the same reason its credit pack is - otherwise the pot
            // reproduces, one bucket over, the very defect this cycle exists to close. For every
            // plan that exists today this is a no-op (no paid plan carries an allowance, so the
            // call CLEARS an already-clear pot and returns without writing), which is precisely
            // why it is safe to run unguarded here: it costs one comparison and it means a
            // future allowance on a paid plan is served monthly on both cadences, not annually
            // on one of them.
            refillAiAllowance(sub, plan);
            return wrote ? MonthlyCycleOutcome.GRANTED : MonthlyCycleOutcome.ABSORBED;
        } catch (DataIntegrityViolationException e) {
            // Parity with attributeOnRenewal. The real idempotence is the existsBySourceId guard
            // in resetBalance / grantPackCredits; a duplicate that reaches the database is thrown
            // inside CreditService.grantCredits's own transactional proxy, which marks this
            // transaction rollback-only, so in production the commit fails and the pass retries
            // next hour rather than landing here.
            log.info("Duplicate monthly credit cycle attribution detected for subId={}, treating as idempotent skip",
                    subscription == null ? null : subscription.getId());
            return MonthlyCycleOutcome.SKIPPED;
        }
    }

    /**
     * The latest monthly cycle that has started by {@code now} and still lies inside the
     * billing period: the largest {@code N >= 1} with
     * {@code periodStart + N months <= now} and {@code periodStart + N months + 1 day <= periodEnd}.
     * 0 when none is due. Every cycle start is computed from the anchor, never from the
     * previous cycle, so a day-31 anchor yields exactly the same eleven cycles as a day-14
     * one. The bound is the period end rather than a hard-coded 11, so a Stripe period that is
     * not exactly twelve months (schedule phase, proration) is neither over- nor under-granted;
     * the one-day margin is what keeps a period end that lands an hour past
     * {@code periodStart + 12 months} (a DST shift between the UTC instant Stripe computes and
     * a local-time anchor) from turning cycle 12 into a thirteenth pack the hour before the
     * renewal. A cycle that would live less than a day before the renewal is the renewal's.
     */
    static int dueCreditCycle(LocalDateTime periodStart, LocalDateTime periodEnd, LocalDateTime now) {
        if (periodStart == null || periodEnd == null || now == null) {
            return 0;
        }
        int due = 0;
        for (int n = 1; ; n++) {
            LocalDateTime cycleStart = periodStart.plusMonths(n);
            if (cycleStart.isAfter(now) || cycleStart.plusDays(1).isAfter(periodEnd)) {
                break;
            }
            due = n;
        }
        return due;
    }

    /**
     * When this subscription's credits will next be RE-GRANTED, or {@code null} when no next
     * grant can be named. Every such renewal also zeroes the bucket first, but the converse
     * does not hold and this method answers the grant, not the zeroing (see the list below).
     *
     * <p><b>Why a user-facing surface needs this and cannot derive it.</b> "When do my credits
     * come back, and how many" is the one question a wallet cannot answer from its balance, and
     * on a YEARLY subscription the obvious answer is the wrong one: the invoice is annual, the
     * credit pack is monthly ({@link #attributeMonthlyCreditCycle}), so the next billing date is
     * eleven months away from the next grant for most of the year. Anything that printed
     * {@code currentPeriodEnd} for everyone would therefore tell a yearly customer to wait a year
     * for credits that arrive in a fortnight.
     *
     * <p>It is computed HERE, beside {@link #dueCreditCycle}, because the two must agree by
     * construction: this method names the instant, that one decides whether the grant is owed,
     * and they read the same anchor with the same one-day margin. A date computed anywhere else
     * would be a second opinion about a schedule this class owns.
     *
     * <p><b>THE INVARIANT: every instant this method returns is strictly in the future, and is
     * one on which a grant actually happens.</b> Everything else is {@code null}, and callers
     * must render that as "we are not saying" rather than substituting a date of their own:
     * <ul>
     *   <li><b>Not in good standing</b> ({@code past_due}, {@code incomplete}) - the grant rides
     *       on an invoice being PAID, and nobody can date that.</li>
     *   <li><b>A row that grants nothing</b> - see {@link #grantsAnyCredits}. An internal PAYG or
     *       CREDIT_PACK row at quantity zero renews on schedule and is handed no credits, so
     *       naming its renewal date would promise an amount that never arrives. Note what that
     *       trades away: such a row IS still zeroed on its renewal, and this method stays silent
     *       about it. Saying nothing beats naming a date beside an amount nobody will receive,
     *       but a surface that wanted to warn about the reset itself would need its own answer,
     *       not this one.</li>
     *   <li><b>Cancelled, when the answer would be the renewal</b> - but ONLY then. A cancelling
     *       YEARLY row keeps receiving its monthly packs to the end of the year it has already
     *       paid for ({@link #isMonthlyCreditCycleEligible} does not look at the cancel flag, and
     *       neither does the query that feeds it), so it is told about them. Refusing there was
     *       a real defect: it silenced the feature for up to eleven months on exactly the plan
     *       shape it was written for, and at exactly the moment its owner is asking whether
     *       cancelling costs them this month's credits.</li>
     *   <li><b>A date that is not in the future</b> - an internal row waits up to an hour between
     *       its period expiring and the hourly scheduler renewing it, and a delayed
     *       {@code invoice.paid} widens that window on a Stripe row. During it the true answer is
     *       "imminent", which is not an instant; "+1,000 credits on {yesterday}" is not either.</li>
     *   <li><b>A yearly cycle that is due and not yet granted</b> - the same window, on the other
     *       branch. {@code YearlyCreditCycleScheduler} runs hourly, so between a cycle start and
     *       its pass a grant is owed NOW; naming the following cycle there would understate the
     *       wait by a month, and by a month for the whole of any scheduler outage. Both branches
     *       therefore answer "imminent" the same way, with silence, rather than one of them
     *       quietly picking the next date instead.</li>
     *   <li><b>No period end recorded</b> - nothing to compute from.</li>
     * </ul>
     *
     * @param now the instant to answer against (injected, so the caller's clock is testable)
     */
    public static LocalDateTime nextCreditGrantAt(Subscription subscription, LocalDateTime now) {
        if (subscription == null || now == null) {
            return null;
        }
        LocalDateTime periodEnd = subscription.getCurrentPeriodEnd();
        if (periodEnd == null) {
            return null;
        }
        // Deliberately NOT the set BillingController selects on (which includes past_due and
        // incomplete): being selectable as "your current subscription" and being owed a future
        // grant are different questions.
        String status = subscription.getStatus();
        if (status == null || !GRANTING_STATUSES.contains(status.toLowerCase())) {
            return null;
        }
        if (!grantsAnyCredits(subscription)) {
            return null;
        }

        LocalDateTime next = null;
        if (isMonthlyCreditCycleEligible(subscription)) {
            LocalDateTime periodStart = subscription.getCurrentPeriodStart();
            if (dueCreditCycle(periodStart, periodEnd, now) > subscription.getCreditCycleIndex()) {
                // A cycle is owed and the hourly pass has not made it yet. Read from the row's
                // own index rather than from the clock, because that is what the scheduler
                // compares against when it decides whether to grant.
                return null;
            }
            next = nextCreditCycleStart(periodStart, periodEnd, now);
        }
        if (next == null) {
            // Past the last intra-period cycle, or never on one: the next grant is the renewal,
            // which is the one thing a cancelling subscription will not get.
            if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
                return null;
            }
            next = periodEnd;
        }
        // Stated once, for both branches. nextCreditCycleStart already guarantees it; the
        // periodEnd fallback guarantees nothing, and that is where the stale-row case lands.
        return next.isAfter(now) ? next : null;
    }

    /**
     * Whether a renewal of this row hands out any credits at all.
     *
     * <p>A mirror of the branch selection in {@link #attributeOnRenewal}, and it has to be:
     * "your credits come back on the 14th" is a promise about an amount, so a row that renews
     * and grants nothing must name no date. The case that makes this more than defensive is an
     * internal PAYG or CREDIT_PACK row at quantity zero, which {@link #grantsBasePack}
     * deliberately excludes and whose plan carries no included credits either. It is
     * {@code active}, it has a period end, and before this check both wallet surfaces offered it
     * "+5,000 credits" on a date nothing would honour.
     *
     * <p>A NEGATIVE quantity is treated as granting nothing, matching the first branch over
     * there, where it is explicitly "nothing to grant".
     */
    private static boolean grantsAnyCredits(Subscription subscription) {
        Plan plan = subscription.getPlan();
        if (plan == null) {
            return false;
        }
        int creditQuantity = subscription.getCreditQuantity() != null ? subscription.getCreditQuantity() : 0;
        if (grantsBasePack(subscription, creditQuantity)) {
            return creditQuantity >= 0;
        }
        return plan.getIncludedLlmTokens() != null && plan.getIncludedLlmTokens() > 0;
    }

    /**
     * Statuses under which a further grant is actually owed. {@code trialing} is included: the
     * trial ends on an invoice that grants, which is exactly what the caller is asking about.
     */
    private static final java.util.Set<String> GRANTING_STATUSES =
            java.util.Set.of("active", "trialing");

    /**
     * The first monthly cycle start strictly AFTER {@code now} that still lies inside the
     * billing period, or {@code null} when none does.
     *
     * <p>The exact mirror of {@link #dueCreditCycle}: same anchor ({@code periodStart} plus N
     * months, never chained from the previous cycle) and the same one-day margin before
     * {@code periodEnd}, so the cycle this returns is precisely the cycle that method will call
     * due when that instant arrives. Returning {@code null} rather than a twelfth cycle is what
     * hands the caller back to the yearly renewal date instead of inventing a thirteenth pack.
     *
     * <p>Strictly after {@code now}, because a cycle starting exactly now is already due and
     * will be granted by the current pass; the next one a reader is waiting for is the one
     * after it.
     */
    static LocalDateTime nextCreditCycleStart(LocalDateTime periodStart, LocalDateTime periodEnd,
                                              LocalDateTime now) {
        if (periodStart == null || periodEnd == null || now == null) {
            return null;
        }
        for (int n = 1; ; n++) {
            LocalDateTime cycleStart = periodStart.plusMonths(n);
            if (cycleStart.plusDays(1).isAfter(periodEnd)) {
                return null;
            }
            if (cycleStart.isAfter(now)) {
                return cycleStart;
            }
        }
    }

    /**
     * The monthly cycle applies to a PAID yearly subscription that is currently in good
     * standing: {@code trialing} has not paid and {@code past_due} has stopped paying, and an
     * internal row (FREE, comp) renews on its own monthly period through the internal
     * scheduler, never here.
     */
    private static boolean isMonthlyCreditCycleEligible(Subscription sub) {
        return "stripe".equalsIgnoreCase(sub.getProvider())
                && "yearly".equalsIgnoreCase(sub.getCadence())
                && "active".equalsIgnoreCase(sub.getStatus());
    }

    /**
     * Statuses {@code findExpiredInternalSubscriptions} matches on. Re-checked under the lock
     * because that selection query is unlocked: a Stripe upgrade cancels the internal sibling
     * row, and renewing a canceled comp row would grant its 5K onto the wallet the user has
     * meanwhile started paying for.
     */
    private static final java.util.Set<String> INTERNAL_RENEWABLE_STATUSES =
            java.util.Set.of("active", "trialing");

    private static boolean isInternalRenewalEligible(Subscription sub) {
        return "internal".equalsIgnoreCase(sub.getProvider())
                && sub.getStatus() != null
                && INTERNAL_RENEWABLE_STATUSES.contains(sub.getStatus().toLowerCase());
    }

    /**
     * Re-read {@code subscription} by primary key under {@code PESSIMISTIC_WRITE} so the
     * rest of the attribution works on live column values inside this transaction.
     *
     * <p>Callers hand us a {@link Subscription} loaded elsewhere. When that elsewhere is
     * another transaction (the internal renewal scheduler, the Stripe webhook handler)
     * the object is DETACHED, and two things go wrong if we trust it: the balance we read
     * for the {@code PLAN_RESET} audit amount may not be the row's current balance, and
     * any {@code save} through it merges every stale column back over the row. A caller
     * that is already inside a transaction (admin comp grant) gets its own managed
     * instance straight back from the persistence context, so this is a no-op there.
     *
     * <p>Returns EMPTY when there is no row to address (null argument, unsaved entity) or the
     * row has vanished. Whether empty is fatal is the CALLER's decision, and the rule is simply
     * whether that caller WRITES through the entity:
     * <ul>
     *   <li>{@link #attributeOnRenewal} may write (the period advance, {@code resetBalance}
     *       zeroing the balance, the grants, the allowance refill), so it MUST abort on empty
     *       - regardless of whether it was also asked to advance the period. Since V494 the
     *       "already attributed this period" check sits in that caller, so a repeat call can
     *       now write nothing at all; the conclusion is unchanged, only the certainty.</li>
     *   <li>{@link #handleCreditPackChange} only READS the live plan and period to key an
     *       idempotent grant, so it degrades to the caller's instance: nothing is written
     *       through it, and aborting would drop a grant Stripe has already charged for.</li>
     * </ul>
     */
    private Optional<Subscription> resolveManagedForUpdate(Subscription subscription) {
        if (subscription == null || subscription.getId() == null) {
            return Optional.empty();
        }
        Optional<Subscription> managed = subscriptionRepository.findByIdForUpdate(subscription.getId());
        // The lock alone does NOT guarantee fresh values. When the caller's entity is already in
        // this persistence context - which it is for every caller that shares a transaction or
        // an open-in-view EntityManager with us, i.e. the Stripe webhook and the admin grant -
        // the query returns that same first-level-cached instance, unrefreshed, however stale it
        // is. Verified empirically: a value committed by another connection after the caller's
        // load was still invisible after the FOR UPDATE. refresh() is what actually re-reads the
        // row, and it is safe on the row we now hold the lock on.
        if (entityManager != null) {
            managed.ifPresent(entityManager::refresh);
        }
        return managed;
    }

    /**
     * Handle credit pack tier upgrade.
     * With billing_cycle_anchor:NOW, Stripe starts a new cycle and charges full price.
     * We grant the full new pack credits. User keeps their remaining balance.
     *
     * @param userId              the user ID
     * @param callerSubscription  the local subscription entity, possibly detached
     * @param oldCreditQuantity   previous Stripe quantity (kept for logging)
     * @param newCreditQuantity   new Stripe quantity (tier cost)
     */
    @Transactional
    public void handleCreditPackChange(Long userId, Subscription callerSubscription,
                                        int oldCreditQuantity, int newCreditQuantity) {
        try {
            // periodSuffix keys this grant's idempotency, so read it off the live row. Today's
            // only production caller (SubscriptionService, @Transactional at class level) hands
            // us a MANAGED entity, so this resolves to the same instance and buys nothing but
            // the lock; it is here so a future caller reaching this method with a detached row
            // does not silently key the grant on a stale period. Unlike the renewal path this
            // one never WRITES through the entity, so an unresolvable row degrades safely to
            // the caller's copy rather than aborting a grant Stripe has already charged for.
            Subscription subscription = resolveManagedForUpdate(callerSubscription).orElse(callerSubscription);
            Plan plan = subscription.getPlan();
            if (plan == null) {
                log.error("Cannot grant pack-change credits: subscription {} has no plan. userId={}",
                        subscription.getId(), userId);
                return;
            }
            String planCode = plan.getCode();
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
                    callerSubscription.getId());
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
        if (analytics != null) analytics.creditsPurchased(userId, amount, tier);
    }

    /**
     * Grant plan-included credits for plans that have includedLlmTokens (e.g. FREE plan).
     * Used when no credit pack is attached (creditQuantity = 0).
     */
    /** @return true when this call performed the grant, false when it was already made. */
    private boolean grantPlanCredits(Long userId, String sourceId, Plan plan) {
        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Plan credits already granted for sourceId={}, skipping", sourceId);
            return false;
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
        return true;
    }

    /**
     * Grant credit pack credits (PURCHASE) based on the Stripe quantity (tier cost).
     * Uses CreditTierConstants to resolve tier index -> credit amount.
     */
    /**
     * @return true when this call performed the grant, false when it was already made OR
     *         when there was nothing to grant. Callers that read this as "already
     *         attributed this period" must rule out the nothing-to-grant case first -
     *         {@code attributeOnRenewal} does, because treating a negative quantity as
     *         "already attributed" would skip the allowance CLEAR on a plan that grants
     *         none, which is the one case the clear exists for.
     */
    private boolean grantPackCredits(Long userId, String sourceId, int creditQuantity, String planCode) {
        if (creditQuantity < 0) {
            return false;
        }

        if (ledgerRepository.existsBySourceId(sourceId)) {
            log.info("Pack credits already granted for sourceId={}, skipping", sourceId);
            return false;
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
        return true;
    }

    /**
     * Reset balance to zero before renewal re-grant.
     * Creates a PLAN_RESET ledger entry for audit trail.
     *
     * <p><b>Contract: {@code subscription} MUST be a managed instance carrying the row's
     * live values</b> - callers get one from {@link #resolveManagedForUpdate}. This method
     * both reads the balance (to record how much the reset absorbed) and zeroes it, so a
     * detached or stale instance produces a {@code PLAN_RESET} row whose amount does not
     * match what was actually absorbed, and permanently mis-states the ledger. That is not
     * hypothetical: it happened in production on an account whose wallet had moved between
     * load and reset.
     */
    /**
     * Set the subscription's AI allowance to what its plan grants (V494).
     *
     * <p>Separate from {@link #resetBalance}, which early-returns when the sub bucket
     * is already zero - hooking the refill there would have skipped it for exactly the
     * accounts that had spent everything, i.e. the ones that need it.
     *
     * <p>No ledger row: this pot is an entitlement refresh, not a credit grant the
     * user could spend anywhere. The debits that draw it are already itemised.
     */
    private void refillAiAllowance(Subscription subscription, Plan plan) {
        if (subscription == null || plan == null) return;
        Integer allowance = emailVerified(subscription) ? plan.getIncludedAiCredits() : null;
        // A plan with NO allowance must end up with an EMPTY pot, not an untouched one.
        // AdminPlanService changes the plan in place on the existing subscription row,
        // so a FREE account with credits left that is moved to a paid or comp plan would
        // otherwise keep them forever: never refilled (nothing to refill to) and never
        // cleared, then spent on free-tier agent turns instead of the wallet the account
        // now pays for. Returning early here is what stranded it permanently.
        //
        // Every in-place plan change goes through a renewal, so there is no window:
        // AdminPlanService.assignPlan anchors the new cycle and then calls
        // attributeOnRenewal on the same row, which reaches here in the same request.
        BigDecimal refreshed = allowance == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(Math.max(0, allowance));
        if (refreshed.compareTo(subscription.getAiRemainingCredits()) == 0) return;
        subscription.setAiRemainingCredits(refreshed);
        subscriptionRepository.save(subscription);
        log.info("Refilled AI allowance for subId={} to {} (plan={})",
                subscription.getId(), refreshed, plan.getCode());
    }

    /**
     * Whether this subscription's owner has verified their e-mail (V494).
     *
     * <p>The AI allowance buys real platform-key inference, so it is granted only to a
     * verified account. The FIRST grant inherits that gate for free (it is only reached
     * through {@code UserResolutionService.attributeCreditsIfEligible}, which returns
     * early otherwise), but the MONTHLY RENEWAL does not: the scheduler selects rows on
     * {@code provider='internal'} and an expired period alone, so without this check
     * every unverified throwaway signup is handed a full pot thirty days later,
     * unattended and hourly. That is the same giveaway the creation paths refuse, just
     * delayed - a delay is not a gate.
     *
     * <p>Unresolvable reads as NOT verified. The pot is the one bucket where the safe
     * direction is to withhold: a verified account that is wrongly skipped gets its pot
     * on the next cycle, while a wrongly granted one is inference nobody can take back.
     */
    private boolean emailVerified(Subscription subscription) {
        try {
            com.apimarketplace.auth.domain.BillingCustomer customer = subscription.getBillingCustomer();
            com.apimarketplace.auth.domain.User owner = customer != null ? customer.getUser() : null;
            if (owner == null) {
                log.warn("Cannot resolve the owner of subscription {} - withholding the AI allowance",
                        subscription.getId());
                return false;
            }
            return owner.isEmailVerified();
        } catch (RuntimeException e) {
            // A detached entity or a closed session: the same answer, for the same reason.
            log.warn("Could not read the e-mail-verified flag for subscription {} ({}) - withholding the AI allowance",
                    subscription.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Zero the sub bucket and record a {@code PLAN_RESET} row for the audit trail.
     *
     * <p>The "has this period already been attributed?" check lives in the CALLER (V494):
     * the allowance refill needs the same answer, and asking twice would double the
     * queries on the hourly renewal sweep. Note that this method still returns early on
     * an already-zero balance, which is NOT the same question - a FREE account that spent
     * everything has a zero balance and must still be refilled.
     */
    private void resetBalance(Long userId, String sourceId, Subscription subscription) {
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
