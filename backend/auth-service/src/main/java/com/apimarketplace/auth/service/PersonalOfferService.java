package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.stripe.StripeClient;
import com.stripe.model.Invoice;
import com.stripe.param.InvoiceListParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Account-bound offer policy and durable checkout reservation. Stripe owns the charge, not the bonus. */
@Service
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferService {
    private final UsernameValidator usernameValidator;
    private final PersonalOfferPolicyRepository policies;
    private final PersonalOfferMatrixRepository matrix;
    private final PersonalOfferCheckoutAttemptRepository attempts;
    private final PersonalOfferFirstPaidPurchaseRepository firstPaid;
    private final RewardCodeRepository codes;
    private final RewardRedemptionRepository redemptions;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final BillingCustomerRepository customers;
    private final StripeClient stripe;

    public PersonalOfferService(PersonalOfferPolicyRepository policies,
                                PersonalOfferMatrixRepository matrix,
                                PersonalOfferCheckoutAttemptRepository attempts,
                                PersonalOfferFirstPaidPurchaseRepository firstPaid,
                                RewardCodeRepository codes,
                                RewardRedemptionRepository redemptions,
                                UserRepository users,
                                SubscriptionRepository subscriptions,
                                BillingCustomerRepository customers,
                                StripeClient stripe,
                                UsernameValidator usernameValidator) {
        this.policies = policies;
        this.matrix = matrix;
        this.attempts = attempts;
        this.firstPaid = firstPaid;
        this.codes = codes;
        this.redemptions = redemptions;
        this.users = users;
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.stripe = stripe;
        this.usernameValidator = usernameValidator;
    }

    public record IssuedOffer(Long offerId, String code, Instant issuedAt, Instant expiresAt,
                              Long policyId, int policyVersion) {}
    public record PlanBonus(String planCode, int bonusCredits, double paygFaceValueUsd, String status) {}
    /** One pack's preview, and the offer's bonus tiers ({@link PersonalOfferSteps}) for the page's ladder. */
    public record OfferPreview(String status, Long offerId, int offerVersion, Instant expiresAt,
                               int monthlyCredits, String billingCycle, List<PlanBonus> plans,
                               Integer nextEligibleMonthlyCredits, List<PersonalOfferSteps.Step> steps) {}
    /**
     * Where the account's offer stands. While a checkout is open, {@code reserved*} name the
     * plan, pack and cycle it holds: past the offer's own deadline that reservation is the only
     * one that can still be paid, and the page opens on it.
     */
    public record CurrentOffer(String status, Long offerId, Integer offerVersion, Instant expiresAt,
                               String code, Integer reservedBonusCredits, Integer grantedCredits,
                               UUID offerAttemptId, Instant sessionExpiresAt,
                               String reservedPlanCode, Integer reservedCreditTierIndex, String reservedBillingCycle) {
        public CurrentOffer(String status, Long offerId, Integer offerVersion, Instant expiresAt,
                            String code, Integer reservedBonusCredits, Integer grantedCredits,
                            UUID offerAttemptId, Instant sessionExpiresAt) {
            this(status, offerId, offerVersion, expiresAt, code, reservedBonusCredits, grantedCredits,
                    offerAttemptId, sessionExpiresAt, null, null, null);
        }
    }
    public record PreparedCheckout(PersonalOfferCheckoutAttempt attempt, boolean reused) {}
    public record CheckoutIdentity(String customerId, String nonce) {}

    public Optional<PersonalOfferPolicy> activePolicy(String campaignKey) {
        return policies.findByCampaignKeyAndState(campaignKey, "ACTIVE");
    }

    public Optional<PersonalOfferPolicy> policyById(Long policyId) {
        return policies.findById(policyId);
    }

    public List<PersonalOfferMatrix> matrixForPolicy(Long policyId) {
        return matrix.findByPolicyId(policyId);
    }

    @Transactional(readOnly = true)
    public Optional<IssuedOffer> readCurrentForUser(Long userId) {
        if (userId == null) return Optional.empty();
        return codes.findByRecipientUserIdAndCampaignKey(userId, "free-credit-upgrade")
                .flatMap(code -> policies.findById(code.getPolicyVersionId())
                        .map(policy -> issued(code, policy)));
    }

    @Transactional(readOnly = true)
    public boolean hasFirstPaidPurchase(Long userId) {
        return firstPaid.findById(userId).map(row -> !"VERIFIED_NEW".equals(row.getStatus())).orElse(false);
    }

    /** First-payment history is checked against Stripe before an old FREE account can receive an offer. */
    @Transactional
    public boolean verifyNoPriorPaidPurchase(Long userId) {
        PersonalOfferFirstPaidPurchase record = firstPaid.findById(userId).orElse(null);
        if (record != null && "PAID".equals(record.getStatus())) return false;
        if (record != null && "VERIFIED_NEW".equals(record.getStatus())) return true;
        BillingCustomer customer = customers.findByUserId(userId).orElse(null);
        if (customer == null || customer.getProviderCustomerId() == null) {
            // A detached customer can hide old charges. Only an account with no Stripe
            // subscription history is safely new without a provider customer.
            if (subscriptions.hasStripeHistory(userId)) return false;
            markHistory(userId, "VERIFIED_NEW", null, null, null);
            return true;
        }
        try {
            var params = InvoiceListParams.builder().setCustomer(customer.getProviderCustomerId()).setLimit(100L).build();
            for (Invoice invoice : stripe.invoices().list(params).autoPagingIterable()) {
                if (!"paid".equalsIgnoreCase(invoice.getStatus()) || invoice.getAmountPaid() == null
                        || invoice.getAmountPaid() <= 0) continue;
                String reason = invoice.getBillingReason();
                if (reason == null) return false; // ambiguous invoice: fail closed
                if (reason.startsWith("subscription_")) {
                    markHistory(userId, "PAID", invoice.getId(), null,
                            invoice.getStatusTransitions() != null && invoice.getStatusTransitions().getPaidAt() != null
                                    ? Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt()) : Instant.now());
                    return false;
                }
            }
            markHistory(userId, "VERIFIED_NEW", null, null, null);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void markHistory(Long userId, String status, String invoiceId, String subscriptionId, Instant paidAt) {
        PersonalOfferFirstPaidPurchase row = firstPaid.findById(userId).orElseGet(() -> {
            PersonalOfferFirstPaidPurchase created = new PersonalOfferFirstPaidPurchase();
            created.setUserId(userId);
            return created;
        });
        row.setStatus(status);
        row.setInvoiceId(invoiceId);
        row.setProviderSubscriptionId(subscriptionId);
        row.setPaidAt(paidAt);
        row.setVerifiedAt(Instant.now());
        firstPaid.save(row);
    }

    /** The lifecycle worker checks exhaustion and recent recharge; this is the final issue guard. */
    @Transactional
    public Optional<IssuedOffer> issueForEligible(Long userId, String campaignKey, Instant now) {
        if (userId == null || campaignKey == null || now == null) return Optional.empty();
        if (!verifyNoPriorPaidPurchase(userId)) return Optional.empty();
        User user = users.lockForPersonalOffer(userId).orElse(null);
        if (user == null || !user.isEnabled() || user.getDeactivatedAt() != null
                || !user.isEmailVerified() || !user.isMarketingConsent()) return Optional.empty();
        Optional<RewardCode> existing = codes.findByRecipientUserIdAndCampaignKey(userId, campaignKey);
        if (existing.isPresent()) {
            return policies.findById(existing.get().getPolicyVersionId())
                    .map(policy -> issued(existing.get(), policy));
        }
        PersonalOfferPolicy policy = activePolicy(campaignKey).orElse(null);
        if (policy == null || !isFreeAccount(userId) || hasFirstPaidPurchase(userId)
                || hasConversionReward(userId)) return Optional.empty();
        RewardCode code = new RewardCode();
        code.setCode(newCode(user, policy.getValidityHours()));
        code.setProgram(RewardProgram.PERSONAL_UPGRADE);
        code.setRecipientUserId(userId);
        code.setCampaignKey(campaignKey);
        code.setPolicyVersionId(policy.getId());
        code.setIssuedAt(now);
        code.setBenefitKind(BenefitKind.CREDIT_GRANT);
        code.setBenefitTrigger(BenefitTrigger.PAID_CONVERSION);
        code.setBenefitAmount(0);
        code.setOwnerRewardKind(OwnerRewardKind.NONE);
        code.setClawbackEnabled(true);
        code.setCapScope(CapScope.NONE);
        code.setValidFrom(now);
        code.setValidUntil(now.plus(policy.getValidityHours(), ChronoUnit.HOURS));
        code.setActive(true);
        codes.saveAndFlush(code);
        return Optional.of(issued(code, policy));
    }

    private boolean isFreeAccount(Long userId) {
        return subscriptions.findActiveByUserId(userId)
                .map(sub -> sub.getPlan() != null && "FREE".equalsIgnoreCase(sub.getPlan().getCode()))
                .orElse(false);
    }

    private boolean hasConversionReward(Long userId) {
        return redemptions.findByRedeemerUserIdAndProgram(userId, RewardProgram.REFERRAL).isPresent()
                || redemptions.findByRedeemerUserIdAndProgram(userId, RewardProgram.PARTNER).isPresent();
    }

    private String newCode(User user, int validityHours) {
        String name = "MEMBER";
        String value = user.getUsername();
        // Some provider profiles use an email as username. Never include it in a coupon.
        if (value != null && !value.isBlank() && !value.contains("@")) {
            String normalized = usernameValidator.normalize(value).toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9_-]+", "-").replaceAll("^[-_]+|[-_]+$", "");
            if (!normalized.isEmpty()) name = normalized;
        }
        String base = name + "-BONUS-" + validityHours + "H";
        // The transaction-level name lock serializes recipients with the same normalized name.
        // The global case-insensitive code constraint also guards other reward programs.
        codes.lockPersonalOfferCodeName("personal-offer-code:" + base);
        if (codes.findByCodeIgnoreCase(base).isEmpty()) return base;
        long suffix = codes.countByCodeStartingWithIgnoreCase(base) + 1;
        String candidate;
        do {
            candidate = base + "-" + suffix++;
        } while (codes.findByCodeIgnoreCase(candidate).isPresent());
        return candidate;
    }

    private IssuedOffer issued(RewardCode code, PersonalOfferPolicy policy) {
        return new IssuedOffer(code.getId(), code.getCode(), code.getIssuedAt(), code.getValidUntil(),
                policy.getId(), policy.getVersion());
    }

    @Transactional(readOnly = true)
    public OfferPreview preview(Long userId, Long offerId, String rawCode, int tierIndex, String cadence) {
        if (userId == null) throw new OfferException("OFFER_UNAVAILABLE");
        validateCadence(cadence);
        int monthlyCredits = CreditTierConstants.getCreditAmount(tierIndex);
        RewardCode offer = resolveOwnedOffer(userId, offerId, rawCode);
        PersonalOfferPolicy policy = policies.findById(offer.getPolicyVersionId())
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        PersonalOfferCheckoutAttempt reservation = null;
        if (!offer.isRedeemableAt(Instant.now())) {
            reservation = attempts.findByRecipientUserIdOrderByCreatedAtDesc(userId).stream()
                    .filter(a -> Objects.equals(a.getRewardCodeId(), offer.getId())
                            && a.getCreditTierIndex() == tierIndex && cadence.equals(a.getCadence())
                            && "OPEN".equals(a.getStatus()) && a.getSessionExpiresAt().isAfter(Instant.now()))
                    .findFirst().orElse(null);
            if (reservation == null) throw new OfferException("OFFER_EXPIRED");
        }
        if (hasFirstPaidPurchase(userId)) throw new OfferException("OFFER_ALREADY_USED");
        // The same refusal the checkout makes, said before a bonus is shown that it would refuse.
        if (!policy.isAllowConversionStack() && hasConversionReward(userId)) throw new OfferException("OFFER_CONFLICT");
        final PersonalOfferCheckoutAttempt reserved = reservation;
        List<PlanBonus> plans = List.of("STARTER", "PRO", "TEAM").stream().map(plan -> {
            if (reserved != null && !reserved.getPlanCode().equals(plan)) {
                return new PlanBonus(plan, 0, 0, "UNAVAILABLE");
            }
            if ("STARTER".equals(plan) && tierIndex > CreditTierConstants.STARTER_MAX_TIER_INDEX) {
                return new PlanBonus(plan, 0, 0, "UNAVAILABLE");
            }
            return matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(policy.getId(), plan, monthlyCredits)
                    .map(row -> new PlanBonus(plan, row.getBonusCredits(),
                            (double) row.getBonusCredits() / policy.getPaygCreditsPerUsd(),
                            row.getBonusCredits() > 0 ? "ELIGIBLE" : "NO_BONUS"))
                    .orElseGet(() -> new PlanBonus(plan, 0, 0, "UNAVAILABLE"));
        }).toList();
        List<PersonalOfferMatrix> cells = matrix.findByPolicyId(policy.getId());
        Integer next = cells.stream()
                .filter(row -> row.getBonusCredits() > 0 && row.getMonthlyCredits() > monthlyCredits)
                .map(PersonalOfferMatrix::getMonthlyCredits).min(Integer::compareTo).orElse(null);
        return new OfferPreview(reservation == null ? "AVAILABLE" : "CHECKOUT_OPEN",
                offer.getId(), policy.getVersion(), offer.getValidUntil(),
                monthlyCredits, cadence, plans, next, PersonalOfferSteps.of(cells));
    }

    @Transactional(readOnly = true)
    public CurrentOffer current(Long userId) {
        RewardCode code = codes.findByRecipientUserIdAndCampaignKey(userId, "free-credit-upgrade").orElse(null);
        if (code == null) return new CurrentOffer("NONE", null, null, null, null, null, null, null, null);
        PersonalOfferPolicy policy = policies.findById(code.getPolicyVersionId()).orElse(null);
        Integer version = policy == null ? null : policy.getVersion();
        RewardRedemption grant = redemptions.findByRedeemerUserIdAndRewardCodeId(userId, code.getId()).orElse(null);
        if (grant != null) {
            String status = grant.getStatus() == RewardStatus.CLAWED_BACK ? "CLAWED_BACK"
                    : grant.getStatus() == RewardStatus.RELEASED ? "GRANTED" : "PROCESSING";
            return new CurrentOffer(status, code.getId(), version, code.getValidUntil(), null, null,
                    grant.getStatus() == RewardStatus.RELEASED ? grant.getRedeemerRewardAmount() : null,
                    grant.getOfferAttemptId(), null);
        }
        var latest = attempts.findByRecipientUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(attempt -> Objects.equals(attempt.getRewardCodeId(), code.getId()))
                .findFirst().orElse(null);
        if (latest != null && Set.of("PAID", "GRANTED", "NO_BONUS").contains(latest.getStatus()))
            return new CurrentOffer("NO_BONUS".equals(latest.getStatus()) ? "NO_BONUS"
                    : "GRANTED".equals(latest.getStatus()) ? "GRANTED" : "PROCESSING",
                    code.getId(), version, code.getValidUntil(), null,
                    latest.getBonusCredits(), "NO_BONUS".equals(latest.getStatus()) ? 0
                            : "GRANTED".equals(latest.getStatus()) ? latest.getBonusCredits() : null,
                    latest.getId(), latest.getSessionExpiresAt());
        if (latest != null && "REVIEW_REQUIRED".equals(latest.getStatus()))
            return new CurrentOffer("REVIEW_REQUIRED", code.getId(), version, code.getValidUntil(),
                    null, latest.getBonusCredits(), null, latest.getId(), latest.getSessionExpiresAt());
        PersonalOfferFirstPaidPurchase paid = firstPaid.findById(userId).orElse(null);
        if (paid != null && "PAID".equals(paid.getStatus())) {
            boolean sameAttempt = latest != null && ((paid.getInvoiceId() != null
                    && Objects.equals(paid.getInvoiceId(), latest.getStripeInvoiceId()))
                    || (paid.getProviderSubscriptionId() != null
                    && Objects.equals(paid.getProviderSubscriptionId(), latest.getStripeSubscriptionId())));
            return new CurrentOffer(sameAttempt ? "PROCESSING" : "ALREADY_USED",
                    code.getId(), version, code.getValidUntil(), null,
                    sameAttempt ? latest.getBonusCredits() : null, null,
                    sameAttempt ? latest.getId() : null, sameAttempt ? latest.getSessionExpiresAt() : null);
        }
        if (latest != null && "COMPLETED".equals(latest.getStatus()))
            return new CurrentOffer("PENDING_PAYMENT", code.getId(), version, code.getValidUntil(), null,
                    latest.getBonusCredits(), null, latest.getId(), latest.getSessionExpiresAt());
        if (latest != null && "OPEN".equals(latest.getStatus()) && latest.getSessionExpiresAt().isAfter(Instant.now()))
            return new CurrentOffer("CHECKOUT_OPEN", code.getId(), version, code.getValidUntil(), null,
                    latest.getBonusCredits(), null, latest.getId(), latest.getSessionExpiresAt(),
                    latest.getPlanCode(), latest.getCreditTierIndex(), latest.getCadence());
        if (latest != null && "CREATING".equals(latest.getStatus()))
            // A checkout being created, or whose creation is uncertain: settles within minutes.
            return new CurrentOffer("CHECKOUT_CREATING", code.getId(), version, code.getValidUntil(), null,
                    latest.getBonusCredits(), null, latest.getId(), latest.getSessionExpiresAt());
        return new CurrentOffer(!code.isActive() ? "DISABLED" : code.getValidUntil().isBefore(Instant.now())
                ? "EXPIRED" : "AVAILABLE", code.getId(), version, code.getValidUntil(),
                code.isActive() ? code.getCode() : null, null, null, null, null);
    }

    private RewardCode resolveOwnedOffer(Long userId, Long offerId, String rawCode) {
        RewardCode code = offerId != null ? codes.findById(offerId).orElse(null)
                : rawCode == null ? codes.findByRecipientUserIdAndCampaignKey(userId, "free-credit-upgrade").orElse(null)
                : codes.findByCodeIgnoreCase(rawCode.trim()).orElse(null);
        if (code == null || code.getProgram() != RewardProgram.PERSONAL_UPGRADE
                || !Objects.equals(code.getRecipientUserId(), userId)) throw new OfferException("OFFER_UNAVAILABLE");
        return code;
    }

    /** Empty means an ordinary reward code; a foreign personal code is deliberately opaque. */
    @Transactional(readOnly = true)
    public Optional<IssuedOffer> recognizePersonalCode(Long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return Optional.empty();
        RewardCode code = codes.findByCodeIgnoreCase(rawCode.trim()).orElse(null);
        if (code == null || code.getProgram() != RewardProgram.PERSONAL_UPGRADE) return Optional.empty();
        if (!Objects.equals(code.getRecipientUserId(), userId)) throw new OfferException("OFFER_UNAVAILABLE");
        if (!code.isRedeemableAt(Instant.now())) throw new OfferException("OFFER_EXPIRED");
        return policies.findById(code.getPolicyVersionId()).map(policy -> issued(code, policy));
    }

    private void validateCadence(String cadence) {
        if (!"monthly".equals(cadence) && !"yearly".equals(cadence))
            throw new OfferException("OFFER_UNAVAILABLE");
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public PreparedCheckout prepareCheckout(Long userId, Long offerId, int offerVersion,
                                            String planCode, int tierIndex, String cadence,
                                            String planPriceId, String creditPriceId) {
        users.lockForPersonalOffer(userId).orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        validateCadence(cadence);
        String plan = planCode == null ? "" : planCode.toUpperCase(Locale.ROOT);
        CreditTierConstants.validateTierForPlan(tierIndex, plan);
        int credits = CreditTierConstants.getCreditAmount(tierIndex);
        RewardCode code = resolveOwnedOffer(userId, offerId, null);
        PersonalOfferPolicy policy = policies.findById(code.getPolicyVersionId())
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        if (policy.getVersion() != offerVersion) throw new OfferException("OFFER_PREVIEW_STALE");
        PersonalOfferMatrix row = matrix.findByPolicyIdAndPlanCodeAndMonthlyCredits(policy.getId(), plan, credits)
                .orElseThrow(() -> new OfferException("PLAN_PACK_UNSUPPORTED"));
        if (hasFirstPaidPurchase(userId) || !isFreeAccount(userId)) throw new OfferException("OFFER_ALREADY_USED");
        if (attempts.findByRecipientUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(existing -> Objects.equals(existing.getRewardCodeId(), offerId)
                        && "REVIEW_REQUIRED".equals(existing.getStatus())))
            throw new OfferException("OFFER_REVIEW_REQUIRED");
        if (!policy.isAllowConversionStack() && hasConversionReward(userId)) throw new OfferException("OFFER_CONFLICT");
        Instant now = Instant.now();
        boolean redeemable = code.isRedeemableAt(now);
        for (PersonalOfferCheckoutAttempt existing : attempts.findPayableForUpdate(userId)) {
            if ("COMPLETED".equals(existing.getStatus())) throw new OfferException("CHECKOUT_IN_PROGRESS");
            if ("OPEN".equals(existing.getStatus()) && existing.getSessionExpiresAt().isAfter(now)
                    && Objects.equals(existing.getRewardCodeId(), offerId)
                    && existing.getPlanCode().equals(plan) && existing.getCreditTierIndex() == tierIndex
                    && existing.getCadence().equals(cadence)) return new PreparedCheckout(existing, true);
            if ("CREATING".equals(existing.getStatus())) {
                if (Objects.equals(existing.getRewardCodeId(), offerId)
                        && existing.getPlanCode().equals(plan) && existing.getCreditTierIndex() == tierIndex
                        && existing.getCadence().equals(cadence)
                        && existing.getSessionExpiresAt().isAfter(now.plus(30, ChronoUnit.MINUTES)))
                    return new PreparedCheckout(existing, true);
                throw new OfferException("CHECKOUT_IN_PROGRESS");
            }
            // Past the deadline only the reservation itself can still be paid: a different choice is
            // refused BEFORE its Stripe session is expired. Expiring first, then refusing, rolled the
            // attempt back to OPEN while Stripe had already killed its session for good.
            if (!redeemable) throw new OfferException("OFFER_EXPIRED");
            if ("OPEN".equals(existing.getStatus()) && existing.getSessionExpiresAt().isAfter(now)
                    && existing.getStripeSessionId() != null) {
                try {
                    stripe.checkout().sessions().expire(existing.getStripeSessionId());
                } catch (Exception cannotExpire) {
                    throw new OfferException("CHECKOUT_IN_PROGRESS");
                }
            }
            if (existing.getStripeSessionId() != null) {
                try {
                    var remote = stripe.checkout().sessions().retrieve(existing.getStripeSessionId());
                    if ("complete".equals(remote.getStatus())) {
                        throw new OfferException("CHECKOUT_IN_PROGRESS");
                    }
                } catch (OfferException confirmedPayment) {
                    throw confirmedPayment;
                } catch (Exception uncertain) {
                    throw new OfferException("CHECKOUT_IN_PROGRESS");
                }
            }
            existing.setStatus("EXPIRED");
            attempts.save(existing);
        }
        if (!redeemable) throw new OfferException("OFFER_EXPIRED");
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setRewardCodeId(code.getId());
        attempt.setRecipientUserId(userId);
        attempt.setPolicyVersionId(policy.getId());
        attempt.setPlanCode(plan);
        attempt.setMonthlyCredits(credits);
        attempt.setCreditTierIndex(tierIndex);
        attempt.setCadence(cadence);
        attempt.setBonusCredits(row.getBonusCredits());
        attempt.setPlanPriceId(planPriceId);
        attempt.setCreditPriceId(creditPriceId);
        // Stripe requires expires_at to be at least 30 minutes after the API call.
        // Persist a fixed five-minute creation margin so an idempotent retry keeps
        // identical parameters without falling below Stripe's lower bound.
        attempt.setSessionExpiresAt(now.plus(Math.min(1440L, policy.getCheckoutHoldMinutes() + 5L), ChronoUnit.MINUTES));
        attempt.setNextReconcileAt(now);
        attempt.setStatus("CREATING");
        return new PreparedCheckout(attempts.saveAndFlush(attempt), false);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public CheckoutIdentity bindCheckoutIdentity(UUID attemptId, String customerId, String nonce) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId)
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        if (attempt.getStripeCustomerId() == null) attempt.setStripeCustomerId(customerId);
        if (attempt.getClientNonce() == null) attempt.setClientNonce(nonce);
        attempts.saveAndFlush(attempt);
        return new CheckoutIdentity(attempt.getStripeCustomerId(), attempt.getClientNonce());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markFirstInvoicePreview(UUID attemptId, long amountDue) {
        if (amountDue <= 0) throw new OfferException("OFFER_FIRST_PAYMENT_REQUIRED");
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId)
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        if (!"CREATING".equals(attempt.getStatus())) throw new OfferException("CHECKOUT_IN_PROGRESS");
        attempt.setFirstInvoicePreviewAmount(amountDue);
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void failUnsubmittedPreview(UUID attemptId) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId).orElse(null);
        if (attempt == null || !"CREATING".equals(attempt.getStatus())
                || attempt.getFirstInvoicePreviewAmount() != null || attempt.getStripeSessionId() != null) return;
        attempt.setStatus("FAILED");
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void failDefinitelyRejectedCheckout(UUID attemptId) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId).orElse(null);
        // A webhook or another idempotent request may already have attached the session.
        // A Stripe validation refusal only releases a reservation still awaiting creation.
        if (attempt == null || !"CREATING".equals(attempt.getStatus())
                || attempt.getStripeSessionId() != null) return;
        attempt.setStatus("FAILED");
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void repairCheckoutCustomer(UUID attemptId, String customerId) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId)
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        attempt.setStripeCustomerId(customerId);
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void attachSession(UUID attemptId, String sessionId, String url, Instant expiresAt) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockById(attemptId)
                .orElseThrow(() -> new OfferException("OFFER_UNAVAILABLE"));
        attempt.setStripeSessionId(sessionId);
        attempt.setSessionUrl(url);
        if (expiresAt != null) attempt.setSessionExpiresAt(expiresAt);
        if ("CREATING".equals(attempt.getStatus())) attempt.setStatus("OPEN");
        attempts.save(attempt);
    }

    @Transactional
    public void checkoutCompleted(Long userId, String sessionId, String subscriptionId, String attemptId) {
        PersonalOfferCheckoutAttempt attempt = attempts.lockByStripeSessionId(sessionId).orElse(null);
        if (attempt == null && attemptId != null) {
            try { attempt = attempts.lockById(UUID.fromString(attemptId)).orElse(null); }
            catch (IllegalArgumentException ignored) { return; }
        }
        if (attempt == null || !Objects.equals(attempt.getRecipientUserId(), userId)
                || !Set.of("OPEN", "CREATING", "COMPLETED").contains(attempt.getStatus())) return;
        if (attempt.getStripeSessionId() != null && !attempt.getStripeSessionId().equals(sessionId)) return;
        attempt.setStripeSessionId(sessionId);
        attempt.setStripeSubscriptionId(subscriptionId);
        attempt.setStatus("COMPLETED");
        attempts.save(attempt);
    }

    @Transactional(readOnly = true)
    public boolean hasPayableReservation(Long userId) {
        for (var attempt : attempts.findByRecipientUserIdOrderByCreatedAtDesc(userId)) {
            if ("COMPLETED".equals(attempt.getStatus())) return true;
            if (!Set.of("CREATING", "OPEN").contains(attempt.getStatus())) continue;
            if (attempt.getSessionExpiresAt().isAfter(Instant.now())) return true;
            if (attempt.getStripeSessionId() == null) return true; // unknown Stripe create outcome
            try {
                var remote = stripe.checkout().sessions().retrieve(attempt.getStripeSessionId());
                if (!"expired".equals(remote.getStatus())) return true;
            } catch (Exception unavailable) {
                return true; // Do not attach a referral while an earlier payment may still settle.
            }
        }
        return false;
    }

    public static class OfferException extends RuntimeException {
        private final String code;
        public OfferException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
