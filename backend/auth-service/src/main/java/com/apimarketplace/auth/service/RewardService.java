package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The single reward-code service: it mints personal referral codes, redeems any
 * program (PROMO, REFERRAL, PARTNER), backs the per-node free-execution claim that
 * preserves the legacy promo benefit, and reports a user's invite stats.
 *
 * <p>Conversion-gated rewards (REFERRAL, PARTNER) only create a {@code PENDING}
 * row here; they are granted to both parties when the redeemer's first paid
 * subscription is captured and the hold elapses (see the conversion hook and the
 * release scheduler). The redeem flow itself is Cloud-only in practice (its REST
 * entry points live on the Stripe-gated BillingController); the free-node claim is
 * invoked from {@code CreditService.consumeForWorkflowNode} only when credits are
 * metered, so it is a no-op in CE.
 */
@Service
public class RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardService.class);

    /** Code alphabet without ambiguous characters (no 0/O, 1/I/L). */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int MINT_RETRIES = 5;

    /** Ledger source types that route to the PAYG bucket (see CreditService). */
    static final String SOURCE_TYPE_REWARD = "REWARD_REFERRAL";
    static final String SOURCE_TYPE_CLAWBACK = "REWARD_CLAWBACK";

    private final RewardCodeRepository codeRepository;
    private final RewardRedemptionRepository redemptionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CreditService creditService;
    private final CreditLedgerRepository ledgerRepository;
    private final SecureRandom random = new SecureRandom();

    /**
     * Optional v2 partner-payout settler. Absent in v1: a PARTNER conversion is
     * recorded and released without money-out (the scheduler logs the deferral).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PartnerPayoutSpi partnerPayoutSpi;

    /** Referral reward, in PAYG credits, granted to BOTH parties on conversion. */
    private final int referralRewardCredits;
    /** Anti-refund hold (days) before a qualified referral reward is released. */
    private final int referralHoldDays;
    /** Optional per-owner soft cap (NULL/absent = uncapped, the default). */
    private final Integer referralSoftCap;

    public RewardService(RewardCodeRepository codeRepository,
                         RewardRedemptionRepository redemptionRepository,
                         SubscriptionRepository subscriptionRepository,
                         // @Lazy breaks the CreditService <-> RewardService cycle: CreditService
                         // setter-injects RewardService (for the free-node claim), and RewardService
                         // needs CreditService only at runtime for grants/clawbacks. Without this the
                         // Spring context fails to start (BeanCurrentlyInCreation).
                         @org.springframework.context.annotation.Lazy CreditService creditService,
                         CreditLedgerRepository ledgerRepository,
                         @Value("${reward.referral.credits:8000}") int referralRewardCredits,
                         @Value("${reward.referral.hold-days:14}") int referralHoldDays,
                         @Value("${reward.referral.soft-cap:#{null}}") Integer referralSoftCap) {
        this.codeRepository = codeRepository;
        this.redemptionRepository = redemptionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.creditService = creditService;
        this.ledgerRepository = ledgerRepository;
        this.referralRewardCredits = referralRewardCredits;
        this.referralHoldDays = referralHoldDays;
        this.referralSoftCap = referralSoftCap;
    }

    public enum RedeemStatus {
        SUCCESS,            // immediate benefit granted (PROMO free-node)
        PENDING_CONVERSION, // attributed; reward granted on the redeemer's paid conversion
        TRACK_ONLY,         // attributed but over the soft cap; held for manual approval
        UNKNOWN_CODE,
        NOT_REDEEMABLE,     // inactive or outside [valid_from, valid_until]
        ALREADY_REDEEMED,   // this user already redeemed this code (or this referral)
        EXHAUSTED,          // global cap reached
        SELF_REFERRAL,      // a user cannot redeem their own referral code
        ALREADY_PAID        // existing paid subscriber: a first conversion can never fire
    }

    /** Outcome of a redeem attempt; {@code redemption} is non-null on the success states. */
    public record RedeemResult(RedeemStatus status, RewardRedemption redemption) {
        static RedeemResult of(RedeemStatus status) { return new RedeemResult(status, null); }
        static RedeemResult of(RedeemStatus status, RewardRedemption r) { return new RedeemResult(status, r); }
        public boolean isSuccess() {
            return status == RedeemStatus.SUCCESS
                    || status == RedeemStatus.PENDING_CONVERSION
                    || status == RedeemStatus.TRACK_ONLY;
        }
    }

    /** A user's referral code plus their progress, for the invite surface. */
    public record InviteStats(String code, long redeemedCount, long pendingCount,
                              long inHoldCount, long rewardedCount, long creditsEarned,
                              Integer softCapLimit, int rewardCredits) {}

    /**
     * Get (or lazily mint) the caller's personal referral code. One REFERRAL code
     * per owner is enforced by {@code uq_reward_code_owner_program}; a concurrent
     * mint that loses the race re-reads the winner's row.
     */
    @Transactional
    public RewardCode getOrMintReferralCode(Long ownerUserId) {
        if (ownerUserId == null) throw new IllegalArgumentException("ownerUserId required");
        return codeRepository.findByOwnerUserIdAndProgram(ownerUserId, RewardProgram.REFERRAL)
                .orElseGet(() -> mintReferralCode(ownerUserId));
    }

    private RewardCode mintReferralCode(Long ownerUserId) {
        org.springframework.dao.DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MINT_RETRIES; attempt++) {
            RewardCode rc = new RewardCode();
            rc.setProgram(RewardProgram.REFERRAL);
            rc.setOwnerUserId(ownerUserId);
            rc.setCode(generateCode());
            rc.setBenefitKind(BenefitKind.CREDIT_GRANT);
            rc.setBenefitAmount(referralRewardCredits);
            rc.setBenefitDurationDays(0);
            rc.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
            rc.setOwnerRewardKind(OwnerRewardKind.CREDIT_GRANT);
            rc.setOwnerRewardAmount(referralRewardCredits);
            rc.setHoldDays(referralHoldDays);
            rc.setClawbackEnabled(true);
            if (referralSoftCap != null) {
                rc.setCapScope(CapScope.PER_OWNER_SOFT);
                rc.setCapLimit(referralSoftCap);
            } else {
                rc.setCapScope(CapScope.NONE);
            }
            rc.setActive(true);
            rc.setValidFrom(Instant.now());
            try {
                return codeRepository.saveAndFlush(rc);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Either a code collision (retry with a new code) or a concurrent
                // mint of THIS owner's code (re-read the winner and stop).
                last = e;
                var existing = codeRepository.findByOwnerUserIdAndProgram(ownerUserId, RewardProgram.REFERRAL);
                if (existing.isPresent()) return existing.get();
            }
        }
        throw last != null ? last : new IllegalStateException("Could not mint referral code");
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Redeem {@code rawCode} for {@code redeemerUserId}. Validates the code,
     * blocks self-referral and double-redeem, rejects an already-paying redeemer
     * for conversion-gated programs (the first conversion can never fire again),
     * reserves a slot race-safely, then records the redemption: an immediate
     * benefit for a redeem-time program, or a PENDING attribution for a
     * conversion-gated one. The unique constraints are the final guard on a
     * concurrent double-redeem (their violation rolls the whole transaction back).
     */
    @Transactional
    public RedeemResult redeem(Long redeemerUserId, String rawCode) {
        if (redeemerUserId == null) return RedeemResult.of(RedeemStatus.UNKNOWN_CODE);
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) return RedeemResult.of(RedeemStatus.UNKNOWN_CODE);

        RewardCode rc = codeRepository.findByCodeIgnoreCase(code).orElse(null);
        if (rc == null) return RedeemResult.of(RedeemStatus.UNKNOWN_CODE);

        Instant now = Instant.now();
        if (!rc.isRedeemableAt(now)) return RedeemResult.of(RedeemStatus.NOT_REDEEMABLE);
        if (rc.isExhausted()) return RedeemResult.of(RedeemStatus.EXHAUSTED);

        if (rc.getProgram() == RewardProgram.REFERRAL
                && redeemerUserId.equals(rc.getOwnerUserId())) {
            return RedeemResult.of(RedeemStatus.SELF_REFERRAL);
        }
        if (redemptionRepository.findByRedeemerUserIdAndRewardCodeId(redeemerUserId, rc.getId()).isPresent()) {
            return RedeemResult.of(RedeemStatus.ALREADY_REDEEMED);
        }
        if (rc.getProgram() == RewardProgram.REFERRAL
                && redemptionRepository.findByRedeemerUserIdAndProgram(redeemerUserId, RewardProgram.REFERRAL).isPresent()) {
            return RedeemResult.of(RedeemStatus.ALREADY_REDEEMED);
        }
        if (rc.getBenefitTrigger() == BenefitTrigger.PAID_CONVERSION
                && hasActivePaidSubscription(redeemerUserId)) {
            return RedeemResult.of(RedeemStatus.ALREADY_PAID);
        }

        // Race-safe reserve: re-checks active + window + GLOBAL cap atomically in-DB.
        if (codeRepository.tryReserveRedemption(rc.getId()) == 0) {
            RewardCode fresh = codeRepository.findById(rc.getId()).orElse(null);
            if (fresh != null && fresh.isExhausted()) return RedeemResult.of(RedeemStatus.EXHAUSTED);
            return RedeemResult.of(RedeemStatus.NOT_REDEEMABLE);
        }

        RewardRedemption r = new RewardRedemption();
        r.setRewardCodeId(rc.getId());
        r.setRedeemerUserId(redeemerUserId);
        r.setOwnerUserId(rc.getOwnerUserId());
        r.setProgram(rc.getProgram());
        r.setRedeemedAt(now);
        r.setActive(true);

        RedeemStatus outcome;
        if (rc.getBenefitTrigger() == BenefitTrigger.REDEEM_TIME
                && rc.getBenefitKind() == BenefitKind.FREE_NODE_COUNTER) {
            // Immediate, time-boxed free-node benefit (the legacy promo shape).
            r.setStatus(RewardStatus.GRANTED);
            r.setBenefitType(RewardCode.BENEFIT_WORKFLOW_NODE_FREE);
            r.setBenefitUntil(now.plus(rc.getBenefitDurationDays(), ChronoUnit.DAYS));
            r.setFreeCreditsCap(rc.getBenefitAmount());
            r.setFreeCreditsUsed(0);
            outcome = RedeemStatus.SUCCESS;
        } else {
            // Conversion-gated: attribute now, grant on the redeemer's paid conversion.
            boolean overSoftCap = isOverSoftCapAfterReserve(rc.getId());
            r.setStatus(overSoftCap ? RewardStatus.TRACK_ONLY : RewardStatus.PENDING);
            outcome = overSoftCap ? RedeemStatus.TRACK_ONLY : RedeemStatus.PENDING_CONVERSION;
        }

        redemptionRepository.save(r);
        log.info("Reward redeemed: redeemer={} code={} program={} status={}",
                redeemerUserId, rc.getCode(), rc.getProgram(), r.getStatus());
        return RedeemResult.of(outcome, r);
    }

    /** A PER_OWNER_SOFT code whose just-reserved counter is past its limit overflows to TRACK_ONLY. */
    private boolean isOverSoftCapAfterReserve(Long codeId) {
        RewardCode fresh = codeRepository.findById(codeId).orElse(null);
        return fresh != null
                && fresh.getCapScope() == CapScope.PER_OWNER_SOFT
                && fresh.getCapLimit() != null
                && fresh.getCurrentRedemptions() > fresh.getCapLimit();
    }

    private boolean hasActivePaidSubscription(Long userId) {
        return subscriptionRepository.findActiveByUserId(userId)
                .filter(s -> "stripe".equalsIgnoreCase(s.getProvider()))
                .map(Subscription::getPlan)
                .map(Plan::getCode)
                .filter(c -> c != null && !"FREE".equalsIgnoreCase(c))
                .isPresent();
    }

    /**
     * Atomically claim one free workflow-node execution for {@code userId} if they
     * hold an active, non-expired, non-exhausted PROMO free-node benefit. Joins the
     * caller's transaction ({@code CreditService.consumeForWorkflowNode}).
     */
    @Transactional
    public boolean claimFreeWorkflowNode(Long userId) {
        if (userId == null) return false;
        return redemptionRepository.claimFreeWorkflowNode(userId, RewardCode.BENEFIT_WORKFLOW_NODE_FREE) > 0;
    }

    /** Active, non-expired free-node benefits for a user (drives the "your rewards" UI). */
    @Transactional(readOnly = true)
    public List<RewardRedemption> getActiveBenefits(Long userId) {
        if (userId == null) return List.of();
        Instant now = Instant.now();
        return redemptionRepository.findByRedeemerUserIdAndActiveTrue(userId).stream()
                .filter(r -> r.getBenefitUntil() != null && r.getBenefitUntil().isAfter(now))
                .toList();
    }

    /** The caller's referral code plus their invite progress. */
    @Transactional
    public InviteStats getInviteStats(Long ownerUserId) {
        RewardCode rc = getOrMintReferralCode(ownerUserId);
        List<RewardRedemption> rows = redemptionRepository.findByOwnerUserId(ownerUserId);
        long pending = rows.stream().filter(r -> r.getStatus() == RewardStatus.PENDING).count();
        long inHold = rows.stream().filter(r -> r.getStatus() == RewardStatus.QUALIFIED).count();
        long rewarded = rows.stream().filter(r -> r.getStatus() == RewardStatus.RELEASED).count();
        long creditsEarned = rows.stream()
                .filter(r -> r.getStatus() == RewardStatus.RELEASED && r.getOwnerRewardAmount() != null)
                .mapToLong(RewardRedemption::getOwnerRewardAmount).sum();
        return new InviteStats(rc.getCode(), rows.size(), pending, inHold, rewarded, creditsEarned,
                rc.getCapLimit(), referralRewardCredits);
    }

    // ----- Conversion lifecycle (called from the Stripe webhook + the scheduler) -----

    /**
     * Mark a redeemer's PENDING conversion-gated redemptions as QUALIFIED on their
     * first captured paid invoice, snapshotting the reward amounts and starting the
     * hold window. Idempotent: only PENDING rows transition, so a replayed webhook
     * (or both invoice.paid and invoice.payment_succeeded firing) is a no-op.
     */
    @Transactional
    public void qualifyOnPaidConversion(Long redeemerUserId, String providerSubscriptionId) {
        if (redeemerUserId == null) return;
        List<RewardRedemption> pendings =
                redemptionRepository.findByRedeemerUserIdAndStatus(redeemerUserId, RewardStatus.PENDING);
        Instant now = Instant.now();
        for (RewardRedemption r : pendings) {
            RewardCode code = codeRepository.findById(r.getRewardCodeId()).orElse(null);
            if (code == null) continue;
            r.setStatus(RewardStatus.QUALIFIED);
            r.setProviderSubscriptionId(providerSubscriptionId);
            r.setQualifiedAt(now);
            r.setReleaseDueAt(now.plus(code.getHoldDays(), ChronoUnit.DAYS));
            r.setRedeemerRewardAmount(code.getBenefitAmount());
            r.setOwnerRewardAmount(code.getOwnerRewardAmount());
            redemptionRepository.save(r);
            log.info("Reward qualified: redemption={} redeemer={} sub={} releaseDue={}",
                    r.getId(), redeemerUserId, providerSubscriptionId, r.getReleaseDueAt());
        }
    }

    /** Ids of qualified rewards whose hold has elapsed (releaser scan, read-only). */
    @Transactional(readOnly = true)
    public List<Long> findDueHoldIds() {
        return redemptionRepository
                .findByStatusAndReleaseDueAtBefore(RewardStatus.QUALIFIED, Instant.now())
                .stream().map(RewardRedemption::getId).toList();
    }

    /** Redeemer ids with a PENDING referral, for the conversion sweeper (read-only). */
    @Transactional(readOnly = true)
    public List<Long> findPendingReferralRedeemerIds() {
        return redemptionRepository
                .findByProgramAndStatus(RewardProgram.REFERRAL, RewardStatus.PENDING)
                .stream().map(RewardRedemption::getRedeemerUserId).distinct().toList();
    }

    /**
     * Release one qualified reward after its hold, granting both parties. Locks the
     * row and re-reads its status inside the lock, so a concurrent clawback that
     * already moved it wins and this is a no-op. Idempotent grants (existsBySourceId)
     * make a crash-retry safe. Each call runs in its own transaction.
     */
    @Transactional
    public boolean releaseOne(Long id) {
        RewardRedemption r = redemptionRepository.lockByIdForUpdate(id).orElse(null);
        if (r == null || r.getStatus() != RewardStatus.QUALIFIED) return false;
        if (r.getRedeemerRewardAmount() != null && r.getRedeemerRewardAmount() > 0) {
            String sid = SOURCE_TYPE_REWARD + "_" + r.getId() + "_REDEEMER";
            grantOnce(r.getRedeemerUserId(), r.getRedeemerRewardAmount(), sid, "Referral reward (redeemer)");
            r.setRewardSourceId(sid);
        }
        if (r.getOwnerUserId() != null && r.getOwnerRewardAmount() != null && r.getOwnerRewardAmount() > 0) {
            if (r.getProgram() == RewardProgram.PARTNER) {
                // v1 inert seam: record the conversion, defer the money-out to v2.
                if (partnerPayoutSpi != null) {
                    partnerPayoutSpi.recordPayout(r);
                } else {
                    log.info("Partner payout deferred (no settler): redemption={} owner={} amount={}",
                            r.getId(), r.getOwnerUserId(), r.getOwnerRewardAmount());
                }
            } else {
                String sid = SOURCE_TYPE_REWARD + "_" + r.getId() + "_OWNER";
                grantOnce(r.getOwnerUserId(), r.getOwnerRewardAmount(), sid, "Referral reward (referrer)");
                r.setOwnerRewardSourceId(sid);
            }
        }
        r.setStatus(RewardStatus.RELEASED);
        r.setReleasedAt(Instant.now());
        redemptionRepository.save(r);
        log.info("Reward released: redemption={} redeemer={} owner={}",
                r.getId(), r.getRedeemerUserId(), r.getOwnerUserId());
        return true;
    }

    /**
     * Conversion sweeper backstop: a swallowed webhook (dispatch exceptions are
     * logged and not retried by Stripe) can leave a paid redeemer PENDING. If the
     * redeemer now holds an active paid subscription, qualify them. Runs per
     * redeemer in its own transaction.
     */
    @Transactional
    public void reconcileRedeemer(Long redeemerUserId) {
        if (redeemerUserId == null) return;
        subscriptionRepository.findActiveByUserId(redeemerUserId)
                .filter(s -> "stripe".equalsIgnoreCase(s.getProvider()))
                .filter(s -> s.getPlan() == null || s.getPlan().getCode() == null
                        || !"FREE".equalsIgnoreCase(s.getPlan().getCode()))
                .filter(s -> s.getProviderSubscriptionId() != null)
                .ifPresent(s -> qualifyOnPaidConversion(redeemerUserId, s.getProviderSubscriptionId()));
    }

    /**
     * Clawback the reward tied to a converting subscription on a full refund or a
     * dispute. Locks the row and re-reads its status: a still-held reward is simply
     * revoked (nothing was granted), an already-released reward is negated for both
     * parties (the negative grant routes to the PAYG bucket). The cap slot is NOT
     * freed (a refunded conversion permanently consumes it). Idempotent + serialized
     * with the releaser via the row lock, so exactly one terminal transition wins.
     */
    @Transactional
    public void clawbackBySubscriptionId(String providerSubscriptionId, String reason) {
        if (providerSubscriptionId == null) return;
        redemptionRepository.findByProviderSubscriptionId(providerSubscriptionId)
                .ifPresent(found -> clawbackRedemption(found.getId(), reason));
    }

    /**
     * Clawback the referee's referral reward (resolved from the refunded/disputed
     * charge's customer). Keying on the referee avoids the wrong-subscription pitfall
     * after a churn-and-resubscribe.
     */
    @Transactional
    public void clawbackByRedeemerUserId(Long redeemerUserId, String reason) {
        if (redeemerUserId == null) return;
        redemptionRepository.findByRedeemerUserIdAndProgram(redeemerUserId, RewardProgram.REFERRAL)
                .ifPresent(found -> clawbackRedemption(found.getId(), reason));
    }

    private void clawbackRedemption(Long redemptionId, String reason) {
        RewardRedemption r = redemptionRepository.lockByIdForUpdate(redemptionId).orElse(null);
        if (r == null) return;
        Instant now = Instant.now();
        if (r.getStatus() == RewardStatus.QUALIFIED) {
            r.setStatus(RewardStatus.CLAWED_BACK);
            r.setClawedBackAt(now);
            redemptionRepository.save(r);
            log.info("Reward revoked in-hold: redemption={} reason={}", r.getId(), reason);
        } else if (r.getStatus() == RewardStatus.RELEASED) {
            clawbackGrant(r.getRedeemerUserId(), r.getRedeemerRewardAmount(),
                    SOURCE_TYPE_CLAWBACK + "_" + r.getId() + "_REDEEMER", reason);
            // Only a credit owner reward (REFERRAL) is clawed back here; a PARTNER
            // payout is deferred to v2, so there is no credit grant to negate.
            if (r.getProgram() != RewardProgram.PARTNER) {
                clawbackGrant(r.getOwnerUserId(), r.getOwnerRewardAmount(),
                        SOURCE_TYPE_CLAWBACK + "_" + r.getId() + "_OWNER", reason);
            }
            r.setStatus(RewardStatus.CLAWED_BACK);
            r.setClawedBackAt(now);
            redemptionRepository.save(r);
            log.info("Reward clawed back post-release: redemption={} reason={}", r.getId(), reason);
        }
    }

    private void grantOnce(Long userId, int amount, String sourceId, String desc) {
        if (userId == null || amount <= 0) return;
        if (ledgerRepository.existsBySourceId(sourceId)) return;
        creditService.grantCredits(userId, BigDecimal.valueOf(amount), SOURCE_TYPE_REWARD, sourceId, desc);
    }

    private void clawbackGrant(Long userId, Integer amount, String sourceId, String reason) {
        if (userId == null || amount == null || amount <= 0) return;
        if (ledgerRepository.existsBySourceId(sourceId)) return;
        creditService.grantCredits(userId, BigDecimal.valueOf(-amount), SOURCE_TYPE_CLAWBACK, sourceId,
                "Referral clawback (" + reason + ")");
    }
}
