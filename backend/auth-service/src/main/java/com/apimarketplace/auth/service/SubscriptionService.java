// src/main/java/com/apimarketplace/auth/service/SubscriptionService.java
package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.apimarketplace.auth.service.util.StripeSubscriptionPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@Transactional
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final StripeClient stripe;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final BillingEventRepository billingEventRepository;
    private final PlanRepository planRepository;
    private final PriceRepository priceRepository;
    private final UserRepository userRepository;
    private final CreditAttributionService creditAttributionService;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;
    private final ObjectMapper objectMapper;
    private final PlanStorageQuotaSyncer quotaSyncer;
    private final OrganizationMemberRepository orgMemberRepository;
    private final OrganizationService organizationService;

    public SubscriptionService(StripeClient stripe,
                               SubscriptionRepository subscriptionRepository,
                               BillingCustomerRepository billingCustomerRepository,
                               BillingEventRepository billingEventRepository,
                               PlanRepository planRepository,
                               PriceRepository priceRepository,
                               UserRepository userRepository,
                               CreditAttributionService creditAttributionService,
                               PendingCreditUpgradeRepository pendingCreditUpgradeRepository,
                               ObjectMapper objectMapper,
                               PlanStorageQuotaSyncer quotaSyncer,
                               OrganizationMemberRepository orgMemberRepository,
                               OrganizationService organizationService) {
        this.stripe = stripe;
        this.subscriptionRepository = subscriptionRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.billingEventRepository = billingEventRepository;
        this.planRepository = planRepository;
        this.priceRepository = priceRepository;
        this.userRepository = userRepository;
        this.creditAttributionService = creditAttributionService;
        this.pendingCreditUpgradeRepository = pendingCreditUpgradeRepository;
        this.objectMapper = objectMapper;
        this.quotaSyncer = quotaSyncer;
        this.orgMemberRepository = orgMemberRepository;
        this.organizationService = organizationService;
    }

    /**
     * Upsert of a local subscription from a Stripe event.
     * All @NotNull fields (billingCustomer, plan, status, dates...) are filled.
     *
     * Idempotent: if another execution inserted the same sub concurrently,
     * we catch the unique constraint and switch to UPDATE.
     */
    public void onSubscriptionUpsert(String eventId,
                                     String providerSubscriptionId,
                                     String status,
                                     Long planId,
                                     String stripePriceId,
                                     LocalDateTime currentPeriodStart,
                                     LocalDateTime currentPeriodEnd,
                                     Long userId,
                                     String nonce) throws StripeException {
        onSubscriptionUpsert(eventId, providerSubscriptionId, status, planId, stripePriceId,
                           currentPeriodStart, currentPeriodEnd, userId, nonce, 0, null);
    }

    public void onSubscriptionUpsert(String eventId,
                                     String providerSubscriptionId,
                                     String status,
                                     Long planId,
                                     String stripePriceId,
                                     LocalDateTime currentPeriodStart,
                                     LocalDateTime currentPeriodEnd,
                                     Long userId,
                                     String nonce,
                                     int creditQuantity,
                                     String creditStripePriceId) throws StripeException {
        try {
            // 1) Retrieve Stripe subscription (expand price for qty/dates/cancelAtPeriodEnd/priceId if needed)
            var retrieveParams = SubscriptionRetrieveParams.builder()
                                                           .addExpand("items.data.price")
                                                           .build();
            var stripeSub = stripe.subscriptions().retrieve(providerSubscriptionId, retrieveParams, null);

            // 2) Resolve BillingCustomer via userId decoded from nonce (priority) or via Stripe customer (fallback)
            BillingCustomer bc;
            if (userId != null) {
                // Use the userId decoded from nonce
                Optional<BillingCustomer> bcOpt = billingCustomerRepository.findByUserId(userId);
                if (bcOpt.isEmpty()) {
                    // Orphaned Stripe subscription: the nonce decoded a userId whose
                    // billing_customers row was deleted (e.g. test-user purge, GDPR delete)
                    // while the Stripe subscription remained active and keeps generating
                    // invoice.paid / customer.subscription.updated events. The original
                    // IllegalStateException bubbled to WebhookController, producing TWO
                    // ERROR lines with stack traces per Stripe event (one here, one in the
                    // controller's outer catch) - observed 8+/day in prod 2026-05 for
                    // userId=103. Logging WARN and returning makes the webhook idempotent-OK
                    // (200 to Stripe → no transport retries) and leaves one structured WARN
                    // per orphaned event instead of two stack traces. The remediation is
                    // operational: cancel the orphan sub on the Stripe side.
                    log.warn("Skipping subscription upsert: BillingCustomer not found for userId={} (event={}, sub={}). " +
                             "Likely an orphan Stripe subscription whose local user was deleted - cancel it on the Stripe Dashboard to stop further webhook events.",
                             userId, eventId, providerSubscriptionId);
                    return;
                }
                bc = bcOpt.get();
                log.info("Found BillingCustomer via decoded userId {} for subscription {}", userId, providerSubscriptionId);
            } else {
                // Fallback: use the Stripe customer
                String customerId = stripeSub.getCustomer();
                if (customerId == null || customerId.isBlank()) {
                    // safety net: attempt to read any sub (very defensive)
                    var list = stripe.subscriptions().list(SubscriptionListParams.builder().setLimit(1L).build());
                    if (list != null && !list.getData().isEmpty()) {
                        customerId = list.getData().get(0).getCustomer();
                    }
                }
                if (customerId == null || customerId.isBlank()) {
                    log.error("Unable to determine the Stripe customer for sub {}", providerSubscriptionId);
                    return;
                }
                
                // Resolve/Create the local BillingCustomer from cus_...
                bc = resolveOrCreateBillingCustomer(customerId);
                log.info("Found BillingCustomer via Stripe customer {} for subscription {}", customerId, providerSubscriptionId);
            }

            // 3) Resolve the local Plan
            Plan plan = planRepository.findById(planId)
                                      .orElseThrow(() -> new IllegalArgumentException("Plan not found id=" + planId));

            // 3.5) Resolve the local Price if stripePriceId provided
            Price price = null;
            String cadence = "monthly"; // Default
            if (stripePriceId != null && !stripePriceId.isBlank()) {
                Optional<Price> priceOpt = priceRepository.findByProviderPriceId(stripePriceId);
                if (priceOpt.isPresent()) {
                    price = priceOpt.get();
                    cadence = price.getCadence();
                } else {
                    log.warn("Price not found for stripePriceId={}, using monthly as default", stripePriceId);
                }
            }

            // 4) Quantity & cancelAtPeriodEnd
            int quantity = 1;
            if (stripeSub.getItems() != null && !stripeSub.getItems().getData().isEmpty()) {
                var q = stripeSub.getItems().getData().get(0).getQuantity();
                if (q != null) quantity = q.intValue();
            }
            boolean cancelAtPeriodEnd = Boolean.TRUE.equals(stripeSub.getCancelAtPeriodEnd());

            // 5) Dates - Use the new SDK 31+ compatible utility
            // The currentPeriodStart/End fields have been moved to SubscriptionItem in Stripe SDK 31
            if (currentPeriodStart == null || currentPeriodEnd == null) {
                StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(stripeSub);
                if (currentPeriodStart == null && period.start() != null) {
                    currentPeriodStart = period.start();
                }
                if (currentPeriodEnd == null && period.end() != null) {
                    currentPeriodEnd = period.end();
                }
            }

            if (currentPeriodStart == null || currentPeriodEnd == null) {
                log.warn("Missing periods for sub {}, continuing but the view may be incomplete.", providerSubscriptionId);
            }

            // 6) Local upsert (normal attempt first)
            Optional<Subscription> existing = subscriptionRepository.findByProviderSubscriptionId(providerSubscriptionId);
            boolean isNewSubscription = !existing.isPresent();
            Subscription local = existing.orElseGet(Subscription::new);

            // keep the old plan and credit quantity for transitions
            String oldPlanCode = (local.getPlan() != null) ? local.getPlan().getCode() : null;
            int oldCreditQuantity = (local.getCreditQuantity() != null) ? local.getCreditQuantity() : 0;

            local.setBillingCustomer(bc);
            local.setPlan(plan);
            local.setPrice(price); // Set the price if found
            local.setCadence(cadence);
            local.setProvider("stripe");
            local.setProviderSubscriptionId(providerSubscriptionId);
            local.setStatus(status != null ? status : "active");
            local.setQuantity(quantity);
            if (currentPeriodStart != null) local.setCurrentPeriodStart(currentPeriodStart);
            if (currentPeriodEnd != null) local.setCurrentPeriodEnd(currentPeriodEnd);
            local.setCancelAtPeriodEnd(cancelAtPeriodEnd);
            local.setCreditQuantity(creditQuantity);
            if (creditStripePriceId != null) {
                priceRepository.findByProviderPriceId(creditStripePriceId).ifPresent(local::setCreditPrice);
            } else {
                local.setCreditPrice(null);
            }
            local.setUpdatedAt(LocalDateTime.now());
            if (local.getCreatedAt() == null) local.setCreatedAt(LocalDateTime.now());

            try {
                subscriptionRepository.save(local);
            } catch (DataIntegrityViolationException dup) {
                // 🔁 Race lost: another thread/process inserted the same sub in between
                log.warn("Uniqueness conflict on provider_subscription_id={}, switching to idempotent UPDATE.",
                         providerSubscriptionId);
                isNewSubscription = false; // Prevent duplicate credit attribution

                // Use a new session to avoid Hibernate issues
                try {
                    Subscription already = subscriptionRepository.findByProviderSubscriptionId(providerSubscriptionId)
                                                                 .orElseThrow(() -> new IllegalStateException("Sub inserted but not found afterwards: " + providerSubscriptionId));

                    // copy the computed state and save
                    String alreadyOldPlanCode = (already.getPlan() != null) ? already.getPlan().getCode() : null;
                    int alreadyOldCreditQuantity = (already.getCreditQuantity() != null) ? already.getCreditQuantity() : 0;

                    already.setBillingCustomer(bc);
                    already.setPlan(plan);
                    already.setPrice(price); // Set the price if found
                    already.setCadence(cadence);
                    already.setProvider("stripe");
                    already.setStatus(status != null ? status : "active");
                    already.setQuantity(quantity);
                    if (currentPeriodStart != null) already.setCurrentPeriodStart(currentPeriodStart);
                    if (currentPeriodEnd != null) already.setCurrentPeriodEnd(currentPeriodEnd);
                    already.setCancelAtPeriodEnd(cancelAtPeriodEnd);
                    already.setCreditQuantity(creditQuantity);
                    if (creditStripePriceId != null) {
                        priceRepository.findByProviderPriceId(creditStripePriceId).ifPresent(already::setCreditPrice);
                    } else {
                        already.setCreditPrice(null);
                    }
                    already.setUpdatedAt(LocalDateTime.now());
                    if (already.getCreatedAt() == null) already.setCreatedAt(LocalDateTime.now());

                    subscriptionRepository.save(already);

                    // for the rest (logs / quota transition), we work with 'already'
                    local = already;
                    oldPlanCode = alreadyOldPlanCode;
                    oldCreditQuantity = alreadyOldCreditQuantity;
                } catch (Exception e) {
                    log.error("Error retrieving/updating the existing subscription: {}", e.getMessage(), e);
                    throw new RuntimeException("Unable to handle the subscription conflict", e);
                }
            }

            log.info("Local subscription upserted id={}, providerSub={}, user={}, plan={}",
                     local.getId(), providerSubscriptionId, bc.getUser().getId(), plan.getCode());

            // Record the subscription update event
            ObjectNode subscriptionEventPayload = objectMapper.createObjectNode();
            subscriptionEventPayload.put("subscriptionId", local.getId());
            subscriptionEventPayload.put("providerSubscriptionId", providerSubscriptionId);
            subscriptionEventPayload.put("userId", bc.getUser().getId());
            subscriptionEventPayload.put("planCode", plan.getCode());
            subscriptionEventPayload.put("planId", planId);
            subscriptionEventPayload.put("status", local.getStatus());
            subscriptionEventPayload.put("cadence", cadence);
            subscriptionEventPayload.put("quantity", quantity);
            subscriptionEventPayload.put("cancelAtPeriodEnd", cancelAtPeriodEnd);
            subscriptionEventPayload.put("creditQuantity", creditQuantity);
            if (creditStripePriceId != null) {
                subscriptionEventPayload.put("creditStripePriceId", creditStripePriceId);
            }
            subscriptionEventPayload.put("action", existing.isPresent() ? "subscription_updated" : "subscription_created");
            subscriptionEventPayload.put("oldPlanCode", oldPlanCode);
            if (nonce != null) {
                subscriptionEventPayload.put("nonce", nonce);
            }
            
            
            BillingEvent subscriptionEvent = new BillingEvent(
                "stripe", 
                "sub_" + providerSubscriptionId + "_" + System.currentTimeMillis(),
                "subscription.upserted",
                subscriptionEventPayload
            );
            billingEventRepository.save(subscriptionEvent);

            // 7) Plan change is now reflected directly via subscription.plan_id update.
            //    The cycle-counter quota system was retired - billing flows through
            //    CreditService at LLM-call time and CreditAttributionService grants
            //    plan credits on renewal.
            String newPlanCode = plan.getCode();
            boolean planChanged = oldPlanCode == null || !oldPlanCode.equals(newPlanCode);
            if (planChanged) {
                log.info("Plan changed for user {}: {} -> {}", bc.getUser().getId(), oldPlanCode, newPlanCode);
            }

            // Sync the tenant's storage quota to the new plan's allowance ONLY
            // on actual plan transitions. Pre-fix this fired on every webhook
            // delivery (Stripe retries, status-only updates, period rollovers)
            // - thrashing updated_at and forcing cache evictions for nothing.
            if (planChanged) {
                quotaSyncer.syncAfterCommit(bc.getUser().getId(), plan);
                // V311: reconcile owned workspaces to the new plan's workspace cap.
                // On downgrade, the excess (most-recent, non-personal) workspaces are
                // paused; on upgrade they are un-paused up to the new cap. Runs in this
                // transaction so the pause state is atomic with the plan change. Never
                // let a reconcile glitch fail the subscription webhook.
                try {
                    organizationService.reconcileWorkspacePauseState(bc.getUser().getId());
                } catch (Exception e) {
                    log.error("Workspace pause reconcile failed for user {} on plan change: {}",
                            bc.getUser().getId(), e.getMessage(), e);
                }
            }

            // 7.5) Enforce local uniqueness: cancel all other active subscriptions
            // MUST happen before credit attribution to avoid "2 active subscriptions" errors
            try {
                if ("active".equalsIgnoreCase(local.getStatus()) || "trialing".equalsIgnoreCase(local.getStatus())) {
                    Long currentUserId = bc.getUser().getId();
                    var statuses = java.util.List.of("active", "trialing", "past_due", "incomplete");
                    var siblings = subscriptionRepository
                            .findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(currentUserId, statuses);

                    boolean changed = false;
                    BigDecimal carryOverCredits = BigDecimal.ZERO;
                    BigDecimal carryOverPaygCredits = BigDecimal.ZERO;
                    for (var s : siblings) {
                        if (!s.getId().equals(local.getId())) {
                            // Transfer sub-bucket credits to the new subscription
                            if (s.getRemainingCredits() != null && s.getRemainingCredits().compareTo(BigDecimal.ZERO) > 0) {
                                carryOverCredits = carryOverCredits.add(s.getRemainingCredits());
                                log.info("Carrying over {} sub credits from subscription {} to {}",
                                        s.getRemainingCredits(), s.getId(), local.getId());
                                s.setRemainingCredits(BigDecimal.ZERO);
                            }
                            // Transfer PAYG-bucket credits. Without this branch a user who
                            // bought a PAYG top-up then upgraded their plan would silently
                            // lose every paid PAYG dollar, because the old subscription row
                            // gets canceled here but its payg_remaining_credits column is
                            // never read by the new active row.
                            if (s.getPaygRemainingCredits() != null && s.getPaygRemainingCredits().compareTo(BigDecimal.ZERO) > 0) {
                                carryOverPaygCredits = carryOverPaygCredits.add(s.getPaygRemainingCredits());
                                log.info("Carrying over {} PAYG credits from subscription {} to {}",
                                        s.getPaygRemainingCredits(), s.getId(), local.getId());
                                s.setPaygRemainingCredits(BigDecimal.ZERO);
                            }
                            s.setStatus("canceled");
                            s.setCancelAtPeriodEnd(true);
                            s.setUpdatedAt(LocalDateTime.now());
                            changed = true;
                            log.info("Subscription {} set to 'canceled' (new active/trialing: {}).",
                                     s.getProviderSubscriptionId(), local.getProviderSubscriptionId());
                        }
                    }
                    if (changed) subscriptionRepository.saveAll(siblings);
                    // Apply carried-over credits to the new subscription. Sub + PAYG are
                    // tracked separately to preserve the V250 two-bucket invariant - a
                    // PAYG dollar must never silently become a sub-cycle credit (it would
                    // be wiped on the next renewal).
                    boolean balanceChanged = false;
                    if (carryOverCredits.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal currentBalance = local.getRemainingCredits() != null ? local.getRemainingCredits() : BigDecimal.ZERO;
                        local.setRemainingCredits(currentBalance.add(carryOverCredits));
                        balanceChanged = true;
                    }
                    if (carryOverPaygCredits.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal currentPayg = local.getPaygRemainingCredits() != null ? local.getPaygRemainingCredits() : BigDecimal.ZERO;
                        local.setPaygRemainingCredits(currentPayg.add(carryOverPaygCredits));
                        balanceChanged = true;
                    }
                    if (balanceChanged) {
                        subscriptionRepository.save(local);
                        log.info("Transferred sub={} + PAYG={} credits to new subscription {} (sub balance: {}, PAYG balance: {})",
                                carryOverCredits, carryOverPaygCredits, local.getId(),
                                local.getRemainingCredits(), local.getPaygRemainingCredits());
                    }
                }
            } catch (Exception e) {
                log.warn("uniqueness enforcement failed (single active subscription) for user {}: {}",
                         bc.getUser().getId(), e.getMessage());
            }

            // 8) Credit attribution (after siblings are canceled to ensure single active subscription)
            try {
                Long currentUserId = bc.getUser().getId();
                if (isNewSubscription) {
                    // New subscription: grant full pack credits
                    creditAttributionService.attributeOnSubscription(currentUserId, local, creditQuantity);
                    log.info("Credits attributed for new subscription: userId={}, plan={}, creditQty={}",
                            currentUserId, plan.getCode(), creditQuantity);
                } else if (oldPlanCode != null && !oldPlanCode.equals(plan.getCode())) {
                    // Plan change: features/quotas only, NO credit logic
                    log.info("Plan change detected: {} -> {} for userId={}, no credit adjustment (features only)",
                            oldPlanCode, plan.getCode(), currentUserId);
                } else if (oldCreditQuantity != creditQuantity) {
                    // Credit pack tier changed. Two possible origins:
                    //   (a) Option A flow (one-shot invoice already paid + grant routed via
                    //       invoice.paid webhook): a non-FAILED PendingCreditUpgrade row exists
                    //       for this subscription with target_credit_quantity = creditQuantity.
                    //       Match on target qty (not time): deterministic across late webhook
                    //       deliveries and Stripe retries.
                    //   (b) Legacy / external path (e.g. Stripe Dashboard edit, schedule
                    //       transition): no matching row, fall through to handleCreditPackChange.
                    boolean handledByOptionA = providerSubscriptionId != null
                            && !pendingCreditUpgradeRepository.findActiveForSubscriptionAndQty(
                                    providerSubscriptionId, creditQuantity)
                            .isEmpty();
                    if (handledByOptionA) {
                        log.info("Skip legacy handleCreditPackChange: PendingCreditUpgrade owns the grant. " +
                                "userId={}, plan={}, sub={}, creditQty {} -> {}",
                                currentUserId, plan.getCode(), providerSubscriptionId,
                                oldCreditQuantity, creditQuantity);
                    } else {
                        creditAttributionService.handleCreditPackChange(
                                currentUserId, local, oldCreditQuantity, creditQuantity);
                        log.info("Credits granted for pack change (legacy path): userId={}, plan={}, creditQty {} -> {}",
                                currentUserId, plan.getCode(), oldCreditQuantity, creditQuantity);
                    }
                }
            } catch (Exception e) {
                log.warn("Credit attribution failed (non-blocking): {}", e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("onSubscriptionUpsert failed (event={}, sub={}): {}", eventId, providerSubscriptionId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Fallback version for compatibility with existing callers.
     */
    public void onSubscriptionUpsert(String eventId,
                                     String providerSubscriptionId,
                                     String status,
                                     Long planId,
                                     String stripePriceId,
                                     LocalDateTime currentPeriodStart,
                                     LocalDateTime currentPeriodEnd) throws StripeException {
        onSubscriptionUpsert(eventId, providerSubscriptionId, status, planId, stripePriceId,
                           currentPeriodStart, currentPeriodEnd, null, null, 0, null);
    }

    /**
     * Cancels a subscription locally (used on customer.subscription.deleted).
     * Idempotent: if already canceled, we simply set the expected state.
     */
    public void cancelSubscription(String providerSubscriptionId) {
        subscriptionRepository.findByProviderSubscriptionId(providerSubscriptionId).ifPresent(sub -> {
            sub.setStatus("canceled");
            sub.setCancelAtPeriodEnd(true);
            sub.setUpdatedAt(LocalDateTime.now());
            // optionally, you can also set currentPeriodEnd when forcing immediate cancellation
            subscriptionRepository.save(sub);
            log.info("Local subscription canceled providerSubId={}", providerSubscriptionId);

            // V311: cancellation leaves the owner with no active subscription, so the workspace cap
            // falls back to FREE (1) - pause any now-over-cap workspaces. (customer.subscription.deleted
            // does not flow through onSubscriptionUpsert.)
            reconcileWorkspacesAfterPlanLoss(
                    sub.getBillingCustomer() != null && sub.getBillingCustomer().getUser() != null
                            ? sub.getBillingCustomer().getUser().getId()
                            : null);
        });
    }

    /**
     * Reconcile a user's workspace pause state after their plan effectively dropped without flowing
     * through {@link #onSubscriptionUpsert}: a subscription cancellation, a Stripe customer deletion,
     * or a Stripe-side cancel reconciled locally. All of these revert the owner toward the FREE
     * workspace cap, so any over-cap workspaces must be paused here - the same engine
     * ({@code OrganizationService.reconcileWorkspacePauseState}) the plan-change path uses. Never
     * throws: a reconcile glitch must not fail the cancellation/webhook (mirrors the onSubscriptionUpsert
     * guard). No-op for a null owner.
     */
    public void reconcileWorkspacesAfterPlanLoss(Long ownerId) {
        if (ownerId == null) {
            return;
        }
        try {
            organizationService.reconcileWorkspacePauseState(ownerId);
        } catch (Exception e) {
            log.error("Workspace pause reconcile failed for user {} after plan loss: {}", ownerId, e.getMessage(), e);
        }
    }

    /**
     * Finds the BillingCustomer by cus_...; creates it if needed by looking up the user via metadata.userId.
     * (This method is safe for most cases. In case of extreme races,
     * the unique constraint on the DB side will protect and the caller will re-read the existing entity.)
     */
    private BillingCustomer resolveOrCreateBillingCustomer(String customerId) throws StripeException {
        return billingCustomerRepository.findByProviderCustomerId(customerId).orElseGet(() -> {
            try {
                Customer sc = stripe.customers().retrieve(customerId);
                Long userId = null;
                if (sc.getMetadata() != null) {
                    String uid = sc.getMetadata().get("userId");
                    if (uid != null) {
                        try { userId = Long.valueOf(uid); } catch (NumberFormatException ignored) {}
                    }
                }
                if (userId == null) {
                    throw new IllegalStateException("Stripe customer " + customerId + " without metadata.userId → unable to create BillingCustomer");
                }
                final Long finalUserId = userId;
                User user = userRepository.findById(userId)
                                          .orElseThrow(() -> new IllegalStateException("User " + finalUserId + " not found"));
                BillingCustomer bc = new BillingCustomer(user, "stripe");
                bc.setProviderCustomerId(customerId);
                return billingCustomerRepository.save(bc);
            } catch (StripeException se) {
                throw new RuntimeException(se);
            }
        });
    }

    @Transactional(readOnly = true)
    public boolean existsByProviderSubscriptionId(String providerSubscriptionId) {
        try {
            return subscriptionRepository.existsByProviderSubscriptionId(providerSubscriptionId);
        } catch (Exception e) {
            // Ultra-light fallback if some drivers/versions have issues with exists()
            return subscriptionRepository.findIdByProviderSubscriptionIdLight(providerSubscriptionId).isPresent();
        }    }

    @Transactional(readOnly = true)
    public Optional<com.apimarketplace.auth.domain.Subscription> findByProviderSubscriptionId(String providerSubscriptionId) {
        return subscriptionRepository.findByProviderSubscriptionId(providerSubscriptionId);
    }

}
