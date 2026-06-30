package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.OrganizationMemberQuotaLimitRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * PR11 - per-member quota cap enforcement.
 *
 * <p>Reads the executor's active workspace (default org) and its
 * {@link OrganizationMemberQuotaLimit} row. If a cap is configured on
 * the credits dimension, sums the executor's debits since the org owner's
 * current billing-period start and compares with the cap. Returns an
 * {@link CapDecision#exceeded(...)} when the projected additional cost
 * would push consumption past the cap; otherwise returns
 * {@link CapDecision#allow()}.
 *
 * <p>Enforcement happens against the EXECUTOR's identity, regardless of
 * who pays (Q1=b safety: a member with a 100-credit cap cannot drain
 * 1000 of the owner's credits just because the bill is redirected).
 *
 * <p>Hot-path safety: never throws. On any internal exception (DB
 * hiccup, missing subscription, missing org) returns {@link CapDecision#allow()}
 * so credit consumption stays unblocked. Quota enforcement is a soft
 * guard, not a security boundary - the underlying owner-balance check
 * in {@link CreditService#deductCredits} remains the hard floor.
 *
 * <p>v1 enforces only the {@code credits} dimension; storage + token
 * caps are persisted in the schema for forward compatibility but not
 * yet read by this service. The bookkeeping query
 * {@link CreditLedgerRepository#sumTokensByExecutorSince} is wired up
 * for when token enforcement lands.
 */
@Service
public class MemberQuotaService {

    private static final Logger log = LoggerFactory.getLogger(MemberQuotaService.class);

    private final OrganizationMemberQuotaLimitRepository quotaRepository;
    private final OrganizationMemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CreditLedgerRepository ledgerRepository;

    public MemberQuotaService(OrganizationMemberQuotaLimitRepository quotaRepository,
                              OrganizationMemberRepository memberRepository,
                              SubscriptionRepository subscriptionRepository,
                              CreditLedgerRepository ledgerRepository) {
        this.quotaRepository = quotaRepository;
        this.memberRepository = memberRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Read-only enforcement: would consuming {@code additionalCost} push the
     * executor past their per-member credits cap?
     *
     * <p>Returns {@link CapDecision#allow()} when:
     * <ul>
     *   <li>The executor has no default org, or the default org is personal,
     *       or the executor IS the owner (no redirect, no cap applies).</li>
     *   <li>No {@link OrganizationMemberQuotaLimit} row exists for
     *       {@code (org, user)}.</li>
     *   <li>The row exists but {@code periodCredits} is NULL (no cap on this
     *       dimension).</li>
     *   <li>The owner has no active subscription (degenerate state, never
     *       block a paying customer over a config glitch).</li>
     *   <li>Any internal exception fires.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public CapDecision checkCreditsCap(Long executorUserId, BigDecimal additionalCost) {
        // Read active workspace from MDC (set by common-lib MdcContextFilter from
        // gateway-injected X-Organization-ID). This makes the cap fire against the
        // workspace the user is actively in, not just their persisted default.
        // Audit fix 2026-05-14: previously cap only fired when default==capping-org.
        String activeOrgIdRaw = MDC.get("org");
        return checkCreditsCap(executorUserId, additionalCost, activeOrgIdRaw);
    }

    /**
     * 3-arg variant: caller supplies the active workspace org explicitly.
     * Used directly by paths that have org context but don't go through MDC.
     * activeOrgId == null → falls back to executor's default membership.
     */
    @Transactional(readOnly = true)
    public CapDecision checkCreditsCap(Long executorUserId, BigDecimal additionalCost, String activeOrgIdRaw) {
        if (executorUserId == null || additionalCost == null || additionalCost.signum() <= 0) {
            return CapDecision.allow();
        }
        try {
            // Resolve the membership to check the cap against. Priority:
            //   1. activeOrgId from request context → look up that specific membership
            //   2. fallback to executor's default membership (legacy behavior)
            Optional<OrganizationMember> membershipOpt = Optional.empty();
            if (activeOrgIdRaw != null && !activeOrgIdRaw.isBlank()) {
                try {
                    UUID activeOrgId = UUID.fromString(activeOrgIdRaw);
                    membershipOpt = memberRepository.findByOrganization_IdAndUser_Id(activeOrgId, executorUserId);
                } catch (IllegalArgumentException e) {
                    // malformed org id - fall through to default lookup
                }
            }
            if (membershipOpt.isEmpty()) {
                membershipOpt = memberRepository.findByUser_IdAndIsDefaultTrue(executorUserId);
            }
            if (membershipOpt.isEmpty()) return CapDecision.allow();
            OrganizationMember membership = membershipOpt.get();

            Organization org = membership.getOrganization();
            // Caps apply to ANY org; personal-vs-shared is no longer a
            // billing-relevant distinction (ADR-009). Owner-is-executor still
            // short-circuits since the owner consuming their own credits never
            // bumps a per-member cap.
            if (org == null || org.isDeleted()) return CapDecision.allow();
            if (org.getOwner() == null || org.getOwner().getId() == null) return CapDecision.allow();
            if (org.getOwner().getId().equals(executorUserId)) return CapDecision.allow();

            Optional<OrganizationMemberQuotaLimit> quotaOpt =
                    quotaRepository.findByOrgIdAndUserId(org.getId(), executorUserId);
            if (quotaOpt.isEmpty()) return CapDecision.allow();
            OrganizationMemberQuotaLimit quota = quotaOpt.get();
            if (quota.getPeriodCredits() == null) return CapDecision.allow();

            // Period start = org owner's current billing-cycle start. Falls back
            // to "30 days ago" only if the owner has no subscription row at all
            // (degenerate). Stripe-aligned periods avoid DST / month-end fence-
            // post issues (§9.2 resolved).
            Optional<Subscription> ownerSub = subscriptionRepository
                    .findActiveByUserId(org.getOwner().getId());
            if (ownerSub.isEmpty() || ownerSub.get().getCurrentPeriodStart() == null) {
                return CapDecision.allow();
            }
            LocalDateTime periodStart = ownerSub.get().getCurrentPeriodStart();

            BigDecimal consumed = ledgerRepository.sumDebitedByExecutorSince(executorUserId, periodStart);
            if (consumed == null) consumed = BigDecimal.ZERO;
            BigDecimal projected = consumed.add(additionalCost);

            if (projected.compareTo(quota.getPeriodCredits()) > 0) {
                log.info("Member quota cap hit: executor={} org={} consumed={} additional={} cap={}",
                        executorUserId, org.getId(), consumed, additionalCost, quota.getPeriodCredits());
                return CapDecision.exceeded("credits", consumed, quota.getPeriodCredits());
            }
            return CapDecision.allow();
        } catch (Exception e) {
            log.warn("Quota cap check failed for executor={} (allowing through): {}",
                    executorUserId, e.getMessage());
            return CapDecision.allow();
        }
    }

    /**
     * Cap decision result. Allow OR exceeded with the offending dimension +
     * the consumption snapshot needed to surface a user-friendly message
     * downstream. Immutable record so callers can safely thread it through
     * the consume pipeline.
     */
    public record CapDecision(boolean allowed, String dimension,
                              BigDecimal consumed, BigDecimal cap) {
        public static CapDecision allow() {
            return new CapDecision(true, null, null, null);
        }
        public static CapDecision exceeded(String dimension, BigDecimal consumed, BigDecimal cap) {
            return new CapDecision(false, dimension, consumed, cap);
        }
    }
}
