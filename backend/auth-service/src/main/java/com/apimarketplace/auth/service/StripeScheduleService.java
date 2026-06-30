package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.dto.PlanChangeRequest;
import com.apimarketplace.auth.domain.dto.PlanChangeResult;
import com.apimarketplace.auth.domain.dto.ScheduledChangeInfo;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.SubscriptionScheduleCancelParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.apimarketplace.auth.service.util.StripeSubscriptionPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Service for managing Stripe Subscription Schedules.
 * 
 * Allows scheduling plan changes (especially downgrades) to take effect
 * at the end of the current billing period.
 * 
 * Stripe Best Practices 2024:
 * - Use Subscription Schedules for downgrades
 * - Customer keeps their current plan until the end of the paid period
 * - No complex refund logic needed
 */
@Service
@Transactional
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class StripeScheduleService {

    private static final Logger log = LoggerFactory.getLogger(StripeScheduleService.class);

    private final StripeClient stripe;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final PlanRepository planRepository;
    private final PriceCacheService priceCacheService;
    private final PlanCacheService planCacheService;

    public StripeScheduleService(
            StripeClient stripeClient,
            SubscriptionRepository subscriptionRepository,
            BillingCustomerRepository billingCustomerRepository,
            PlanRepository planRepository,
            PriceCacheService priceCacheService,
            PlanCacheService planCacheService
    ) {
        this.stripe = stripeClient;
        this.subscriptionRepository = subscriptionRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.planRepository = planRepository;
        this.priceCacheService = priceCacheService;
        this.planCacheService = planCacheService;
    }

    /**
     * Schedules a downgrade to take effect at the end of the current billing period.
     * 
     * @param userId User ID
     * @param targetPlanCode Target plan code (must be lower than current plan)
     * @return Result of the scheduled change
     */
    public PlanChangeResult scheduleDowngrade(Long userId, String targetPlanCode) {
        try {
            log.info("Scheduling downgrade for user {} to plan {}", userId, targetPlanCode);

            // 1. Get the active subscription
            Subscription localSub = subscriptionRepository.findActiveByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("No active subscription for user " + userId));

            String currentPlanCode = localSub.getPlan().getCode();
            String providerSubId = localSub.getProviderSubscriptionId();

            if (providerSubId == null || providerSubId.isBlank()) {
                return PlanChangeResult.error("No linked Stripe subscription");
            }

            // 2. Verify this is actually a downgrade
            if (!isDowngrade(currentPlanCode, targetPlanCode)) {
                return PlanChangeResult.error("This is not a downgrade. Use upgrade for higher plans.");
            }

            // 3. Note: createOrUpdateSchedule handles the case where a schedule already exists
            // (it updates instead of creating a new one)

            // 4. Retrieve the Stripe subscription
            com.stripe.model.Subscription stripeSub = stripe.subscriptions().retrieve(providerSubId);
            
            // 5. Get pricing information
            String currentBillingCycle = getCurrentBillingCycle(localSub);
            String currentPriceId = localSub.getPrice() != null ? 
                    localSub.getPrice().getProviderPriceId() : 
                    priceCacheService.getPriceId(currentPlanCode, currentBillingCycle).orElse(null);
            
            String targetPriceId = priceCacheService.getPriceId(targetPlanCode.toUpperCase(), currentBillingCycle)
                    .orElseThrow(() -> new IllegalArgumentException("Price not found for plan " + targetPlanCode));

            // 6. Create the subscription schedule - SDK 31+ uses SubscriptionItem for periods
            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(stripeSub);
            LocalDateTime effectiveDate = period.end();
            Long currentPeriodEnd = period.endEpochOrDefault(30); // fallback: +30 days

            // Resolve current credit item from Stripe
            CreditItem currentCredit = resolveCurrentCreditItem(providerSubId);
            // For downgrade: determine new credit pricing if plan changes credit pack type
            String targetCreditPriceId = priceCacheService.getCreditPriceId(targetPlanCode.toUpperCase(), currentBillingCycle).orElse(null);

            int currentCreditTierIndex = CreditTierConstants.resolveTierIndex(
                    currentCredit.quantity(), currentPlanCode);
            int targetCreditQty = CreditTierConstants.getCreditCost(
                    currentCreditTierIndex, targetPlanCode.toUpperCase());

            SubscriptionSchedule schedule = createOrUpdateSchedule(
                    providerSubId,
                    currentPriceId,
                    targetPriceId,
                    currentPeriodEnd,
                    currentCredit.priceId(),
                    currentCredit.quantity(),
                    targetCreditPriceId,
                    targetCreditQty
            );

            log.info("Downgrade scheduled: schedule={}, user={}, {} -> {}, effective={}",
                    schedule.getId(), userId, currentPlanCode, targetPlanCode, effectiveDate);

            // 7. Update the local subscription
            localSub.setCancelAtPeriodEnd(false); // This is not a cancellation
            localSub.setUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(localSub);

            return PlanChangeResult.scheduledDowngrade(
                    currentPlanCode,
                    targetPlanCode,
                    effectiveDate,
                    schedule.getId()
            );

        } catch (StripeException e) {
            log.error("Stripe error scheduling downgrade for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error("Stripe error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error scheduling downgrade for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error(e.getMessage());
        }
    }

    /**
     * Schedules a billing cycle change (monthly <-> yearly).
     * Takes effect at the end of the current billing period.
     * 
     * @param userId User ID
     * @param targetCycle Target billing cycle ("monthly" or "yearly")
     * @return Result of the scheduled change
     */
    public PlanChangeResult scheduleBillingCycleChange(Long userId, String targetCycle) {
        try {
            log.info("Scheduling billing cycle change for user {} to {}", userId, targetCycle);

            Subscription localSub = subscriptionRepository.findActiveByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("No active subscription"));

            String currentPlanCode = localSub.getPlan().getCode();
            String currentCycle = getCurrentBillingCycle(localSub);
            String providerSubId = localSub.getProviderSubscriptionId();

            if (currentCycle.equals(targetCycle)) {
                return PlanChangeResult.noChange(currentPlanCode);
            }

            // Get the target price
            String targetPriceId = priceCacheService.getPriceId(currentPlanCode, targetCycle)
                    .orElseThrow(() -> new IllegalArgumentException("Price not found for " + currentPlanCode + " " + targetCycle));

            com.stripe.model.Subscription stripeSub = stripe.subscriptions().retrieve(providerSubId);
            // SDK 31+ uses SubscriptionItem for periods
            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(stripeSub);
            Long currentPeriodEnd = period.endEpochOrDefault(30); // fallback: +30 days

            // Note: createOrUpdateSchedule handles the case where a schedule already exists

            String currentPriceId = localSub.getPrice() != null ?
                    localSub.getPrice().getProviderPriceId() :
                    priceCacheService.getPriceId(currentPlanCode, currentCycle).orElse(null);

            // Resolve current credit item from Stripe
            CreditItem currentCredit = resolveCurrentCreditItem(providerSubId);
            // Cycle change: credit pack switches to the new cycle's price
            String targetCreditPriceId = priceCacheService.getCreditPriceId(currentPlanCode, targetCycle).orElse(null);

            // Create the schedule for the cycle change
            SubscriptionSchedule schedule = createOrUpdateSchedule(
                    providerSubId,
                    currentPriceId,
                    targetPriceId,
                    currentPeriodEnd,
                    currentCredit.priceId(),
                    currentCredit.quantity(),
                    targetCreditPriceId,
                    currentCredit.quantity()  // keep same credit qty for cycle changes
            );

            LocalDateTime effectiveDate = period.end() != null 
                    ? period.end() 
                    : LocalDateTime.now().plusDays(30);

            log.info("Billing cycle change scheduled: schedule={}, user={}, {} -> {}, effective={}",
                    schedule.getId(), userId, currentCycle, targetCycle, effectiveDate);

            return PlanChangeResult.builder()
                    .changeType(PlanChangeResult.ChangeType.BILLING_CYCLE_CHANGE)
                    .success(true)
                    .currentPlanCode(currentPlanCode)
                    .targetPlanCode(currentPlanCode)
                    .effectiveDate(effectiveDate)
                    .scheduleId(schedule.getId())
                    .message("Your billing will switch to " + targetCycle + " on " + effectiveDate.toLocalDate())
                    .build();

        } catch (StripeException e) {
            log.error("Stripe error changing billing cycle for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error("Stripe error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error changing billing cycle for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error(e.getMessage());
        }
    }

    /**
     * Schedules a credit tier downgrade to take effect at the end of the current billing period.
     * The user keeps their current credit allocation until renewal.
     *
     * @param userId User ID
     * @param targetCreditTierIndex Target credit tier index (must be lower than current)
     * @return Result of the scheduled change
     */
    public PlanChangeResult scheduleCreditTierDowngrade(Long userId, int targetCreditTierIndex) {
        try {
            log.info("Scheduling credit tier downgrade for user {} to tier {}", userId, targetCreditTierIndex);

            // 1. Get the active subscription
            Subscription localSub = subscriptionRepository.findActiveByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("No active subscription for user " + userId));

            String currentPlanCode = localSub.getPlan().getCode();
            String providerSubId = localSub.getProviderSubscriptionId();

            // 2. Reject plans that don't support credit tiers
            if ("FREE".equalsIgnoreCase(currentPlanCode) || "PAYG".equalsIgnoreCase(currentPlanCode)) {
                return PlanChangeResult.error("Credit tier changes are not available for the " + currentPlanCode + " plan.");
            }

            if (providerSubId == null || providerSubId.isBlank()) {
                return PlanChangeResult.error("No linked Stripe subscription");
            }

            // 3. Validate the tier index for this plan
            CreditTierConstants.validateTierForPlan(targetCreditTierIndex, currentPlanCode);

            // 4. Resolve current credit item from Stripe
            CreditItem currentCredit = resolveCurrentCreditItem(providerSubId);

            if (currentCredit.priceId() == null) {
                log.error("Could not resolve current credit item for subscription {}. No credit pack found.", providerSubId);
                return PlanChangeResult.error("Could not resolve your current credit pack. Please try again or contact support.");
            }

            int targetCreditQty = CreditTierConstants.getCreditCost(targetCreditTierIndex, currentPlanCode);

            // 5. Verify this is actually a downgrade (target qty < current qty)
            if (targetCreditQty >= currentCredit.quantity()) {
                return PlanChangeResult.error("This is not a credit downgrade. Target credit cost ("
                        + targetCreditQty + ") must be less than current (" + currentCredit.quantity() + ").");
            }

            // 6. Retrieve the Stripe subscription and period
            com.stripe.model.Subscription stripeSub = stripe.subscriptions().retrieve(providerSubId);
            String currentBillingCycle = getCurrentBillingCycle(localSub);

            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(stripeSub);
            LocalDateTime effectiveDate = period.end();
            Long currentPeriodEnd = period.endEpochOrDefault(30);

            // 7. Resolve price IDs (plan stays the same, credit price stays the same)
            String currentPriceId = localSub.getPrice() != null ?
                    localSub.getPrice().getProviderPriceId() :
                    priceCacheService.getPriceId(currentPlanCode, currentBillingCycle).orElse(null);
            String creditPriceId = priceCacheService.getCreditPriceId(currentPlanCode, currentBillingCycle)
                    .orElse(currentCredit.priceId()); // fallback to current Stripe price if cache miss

            // 8. Create the schedule: same plan price in both phases, different credit qty
            SubscriptionSchedule schedule = createOrUpdateSchedule(
                    providerSubId,
                    currentPriceId,           // same plan in phase 1
                    currentPriceId,           // same plan in phase 2
                    currentPeriodEnd,
                    currentCredit.priceId(),   // current credit price
                    currentCredit.quantity(),  // current credit qty (phase 1)
                    creditPriceId,            // same credit price
                    targetCreditQty           // reduced credit qty (phase 2)
            );

            log.info("Credit tier downgrade scheduled: schedule={}, user={}, creditQty {} -> {}, effective={}",
                    schedule.getId(), userId, currentCredit.quantity(), targetCreditQty, effectiveDate);

            // 8. Update local subscription
            localSub.setCancelAtPeriodEnd(false);
            localSub.setUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(localSub);

            return PlanChangeResult.scheduledCreditDowngrade(
                    currentPlanCode,
                    effectiveDate,
                    schedule.getId()
            );

        } catch (StripeException e) {
            log.error("Stripe error scheduling credit tier downgrade for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error("Stripe error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error scheduling credit tier downgrade for user {}: {}", userId, e.getMessage(), e);
            return PlanChangeResult.error(e.getMessage());
        }
    }

    /**
     * Gets information about a scheduled change for a user.
     *
     * @param userId User ID
     * @return Scheduled change info if exists, empty otherwise
     */
    public Optional<ScheduledChangeInfo> getScheduledChange(Long userId) {
        try {
            Subscription localSub = subscriptionRepository.findActiveByUserId(userId).orElse(null);
            if (localSub == null || localSub.getProviderSubscriptionId() == null) {
                return Optional.empty();
            }

            Optional<SubscriptionSchedule> scheduleOpt = findActiveSchedule(localSub.getProviderSubscriptionId());
            if (scheduleOpt.isEmpty()) {
                return Optional.empty();
            }

            SubscriptionSchedule schedule = scheduleOpt.get();
            return Optional.of(convertToScheduledChangeInfo(schedule, localSub));

        } catch (Exception e) {
            log.error("Error getting scheduled change for user {}: {}", userId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Cancels a scheduled change and keeps the current subscription as-is.
     * 
     * @param userId User ID
     * @return true if released successfully, false otherwise
     */
    public boolean cancelScheduledChange(Long userId) {
        try {
            Subscription localSub = subscriptionRepository.findActiveByUserId(userId).orElse(null);
            if (localSub == null || localSub.getProviderSubscriptionId() == null) {
                return false;
            }

            Optional<SubscriptionSchedule> scheduleOpt = findActiveSchedule(localSub.getProviderSubscriptionId());
            if (scheduleOpt.isEmpty()) {
                log.info("No active schedule found for user {}", userId);
                return false;
            }

            SubscriptionSchedule schedule = scheduleOpt.get();
            
            // IMPORTANT: Use RELEASE (not cancel) to keep the current subscription intact
            // Cancel = terminates BOTH schedule AND subscription
            // Release = removes schedule but keeps subscription running as-is
            SubscriptionScheduleReleaseParams params = SubscriptionScheduleReleaseParams.builder()
                    .setPreserveCancelDate(false) // Don't preserve any cancel_at date
                    .build();
            
            stripe.subscriptionSchedules().release(schedule.getId(), params);
            
            log.info("Released scheduled change for user {}, schedule={}", userId, schedule.getId());
            return true;

        } catch (StripeException e) {
            log.error("Stripe error releasing scheduled change for user {}: {}", userId, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Error releasing scheduled change for user {}: {}", userId, e.getMessage(), e);
            return false;
        }
    }

    // ======================== PRIVATE HELPERS ========================

    /**
     * Creates or updates a subscription schedule for a plan/cycle change.
     * If the subscription already has an attached schedule, it updates it.
     * Otherwise, creates a new schedule from the subscription.
     * 
     * @param subscriptionId Stripe subscription ID
     * @param currentPriceId Current price ID (for phase 1)
     * @param targetPriceId Target price ID (for phase 2)
     * @param currentPeriodEnd End of current billing period (epoch seconds)
     * @return The created or updated SubscriptionSchedule
     */
    /**
     * Resolves the current credit item (price ID + quantity) from a Stripe subscription.
     */
    private record CreditItem(String priceId, int quantity) {}

    private CreditItem resolveCurrentCreditItem(String providerSubId) {
        try {
            var stripeSub = stripe.subscriptions().retrieve(providerSubId);
            for (var item : stripeSub.getItems().getData()) {
                if (priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                    String priceId = item.getPrice().getId();
                    int qty = item.getQuantity() != null ? item.getQuantity().intValue() : 0;
                    return new CreditItem(priceId, qty);
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve credit item for sub {}: {}", providerSubId, e.getMessage());
        }
        return new CreditItem(null, 0);
    }

    private SubscriptionSchedule createOrUpdateSchedule(
            String subscriptionId,
            String currentPriceId,
            String targetPriceId,
            Long currentPeriodEnd,
            String currentCreditPriceId,
            int currentCreditQty,
            String targetCreditPriceId,
            int targetCreditQty
    ) throws StripeException {
        
        // Check if the subscription already has a schedule attached
        com.stripe.model.Subscription stripeSub = stripe.subscriptions().retrieve(subscriptionId);
        String existingScheduleId = stripeSub.getSchedule();
        
        SubscriptionSchedule schedule;
        Long currentPhaseStart;
        
        if (existingScheduleId != null && !existingScheduleId.isBlank()) {
            // Schedule already exists, retrieve and update it
            log.info("Existing schedule found: {}, updating...", existingScheduleId);
            schedule = stripe.subscriptionSchedules().retrieve(existingScheduleId);
            
            // Get the start_date from the existing first phase
            if (schedule.getPhases() != null && !schedule.getPhases().isEmpty()) {
                currentPhaseStart = schedule.getPhases().get(0).getStartDate();
            } else {
                currentPhaseStart = System.currentTimeMillis() / 1000;
            }
        } else {
            // No existing schedule, create a new one from the subscription
            log.info("Creating new schedule for subscription: {}", subscriptionId);
            SubscriptionScheduleCreateParams createParams = SubscriptionScheduleCreateParams.builder()
                    .setFromSubscription(subscriptionId)
                    .build();
            
            schedule = stripe.subscriptionSchedules().create(createParams);
            
            // Get the start_date from the auto-created first phase
            if (schedule.getPhases() != null && !schedule.getPhases().isEmpty()) {
                currentPhaseStart = schedule.getPhases().get(0).getStartDate();
            } else {
                currentPhaseStart = System.currentTimeMillis() / 1000;
            }
        }
        
        // Define phases: current plan + target plan
        // SDK 31+: first phase MUST have a start_date to anchor the schedule
        var currentPhaseBuilder = SubscriptionScheduleUpdateParams.Phase.builder()
                .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                        .setPrice(currentPriceId)
                        .setQuantity(1L)
                        .build())
                .setStartDate(currentPhaseStart)
                .setEndDate(currentPeriodEnd);
        if (currentCreditPriceId != null && currentCreditQty > 0) {
            currentPhaseBuilder.addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                    .setPrice(currentCreditPriceId)
                    .setQuantity((long) currentCreditQty)
                    .build());
        }
        SubscriptionScheduleUpdateParams.Phase currentPhase = currentPhaseBuilder.build();

        // Second phase: the target plan after the change
        var newPhaseBuilder = SubscriptionScheduleUpdateParams.Phase.builder()
                .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                        .setPrice(targetPriceId)
                        .setQuantity(1L)
                        .build());
        if (targetCreditPriceId != null && targetCreditQty > 0) {
            newPhaseBuilder.addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                    .setPrice(targetCreditPriceId)
                    .setQuantity((long) targetCreditQty)
                    .build());
        }
        SubscriptionScheduleUpdateParams.Phase newPhase = newPhaseBuilder.build();
        
        SubscriptionScheduleUpdateParams updateParams = SubscriptionScheduleUpdateParams.builder()
                .setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .addPhase(currentPhase)
                .addPhase(newPhase)
                .build();
        
        return stripe.subscriptionSchedules().update(schedule.getId(), updateParams);
    }

    /**
     * Finds an active schedule attached to a subscription.
     * SDK 31+: subscription filter no longer exists, we get the schedule ID
     * directly from the subscription object.
     * 
     * @param subscriptionId Stripe subscription ID
     * @return Optional containing the active schedule, or empty if none
     */
    private Optional<SubscriptionSchedule> findActiveSchedule(String subscriptionId) {
        try {
            // Get the Stripe subscription to check for attached schedule
            com.stripe.model.Subscription stripeSub = stripe.subscriptions().retrieve(subscriptionId);
            String scheduleId = stripeSub.getSchedule();
            
            if (scheduleId == null || scheduleId.isBlank()) {
                return Optional.empty();
            }
            
            // Retrieve the schedule
            SubscriptionSchedule schedule = stripe.subscriptionSchedules().retrieve(scheduleId);
            
            // Verify the schedule is active (not_started or active)
            String status = schedule.getStatus();
            if ("not_started".equals(status) || "active".equals(status)) {
                return Optional.of(schedule);
            }
            
            return Optional.empty();
            
        } catch (StripeException e) {
            log.warn("Error finding schedule for subscription {}: {}", subscriptionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Converts a SubscriptionSchedule to a ScheduledChangeInfo DTO.
     */
    private ScheduledChangeInfo convertToScheduledChangeInfo(SubscriptionSchedule schedule, Subscription localSub) {
        // Extract info from phases
        var phases = schedule.getPhases();
        String targetPlanCode = null;
        String targetPriceId = null;
        LocalDateTime effectiveDate = null;
        
        if (phases != null && phases.size() > 1) {
            var futurePhase = phases.get(1);
            if (futurePhase.getItems() != null && !futurePhase.getItems().isEmpty()) {
                // Find the plan price (skip credit pack prices)
                for (var item : futurePhase.getItems()) {
                    String priceId = item.getPrice();
                    if (priceId != null && !priceCacheService.isCreditPackPrice(priceId)) {
                        targetPriceId = priceId;
                        break;
                    }
                }
                // Resolve plan code from the plan price ID
                targetPlanCode = resolvePlanCodeFromPriceId(targetPriceId);
            }
            if (futurePhase.getStartDate() != null) {
                effectiveDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(futurePhase.getStartDate()), ZoneId.systemDefault());
            }
        }

        String currentPlanCode = localSub.getPlan().getCode();
        String currentPlanName = localSub.getPlan().getName();
        String targetPlanName = targetPlanCode != null ?
                planRepository.findByCode(targetPlanCode).map(Plan::getName).orElse(targetPlanCode) : null;

        String currentBillingCycle = getCurrentBillingCycle(localSub);
        String targetBillingCycle = getTargetBillingCycle(targetPriceId);

        boolean isBillingCycleChange = currentPlanCode != null && currentPlanCode.equals(targetPlanCode)
                && !currentBillingCycle.equals(targetBillingCycle);

        // Detect credit tier change: same plan, same billing cycle, different credit quantities
        boolean isCreditTierChange = false;
        if (phases != null && currentPlanCode != null && currentPlanCode.equals(targetPlanCode)
                && currentBillingCycle.equals(targetBillingCycle) && phases.size() > 1) {
            int phase1CreditQty = extractCreditQuantityFromPhase(phases.get(0));
            int phase2CreditQty = extractCreditQuantityFromPhase(phases.get(1));
            log.info("[ScheduledChange] Credit tier detection: samePlan={}, sameCycle={}, phase1Qty={}, phase2Qty={}",
                    currentPlanCode.equals(targetPlanCode), currentBillingCycle.equals(targetBillingCycle),
                    phase1CreditQty, phase2CreditQty);
            isCreditTierChange = phase1CreditQty != phase2CreditQty && (phase1CreditQty > 0 || phase2CreditQty > 0);
        }

        int currentCreditQty = 0;
        int targetCreditQty = 0;
        if (isCreditTierChange && phases.size() > 1) {
            // Stripe stores dollar costs as quantities; convert to credit amounts for display
            int currentCost = extractCreditQuantityFromPhase(phases.get(0));
            int targetCost = extractCreditQuantityFromPhase(phases.get(1));
            currentCreditQty = CreditTierConstants.getCreditAmount(
                    CreditTierConstants.resolveTierIndex(currentCost, currentPlanCode));
            targetCreditQty = CreditTierConstants.getCreditAmount(
                    CreditTierConstants.resolveTierIndex(targetCost, targetPlanCode));
        }

        String changeType;
        String userMessage;
        if (isCreditTierChange) {
            changeType = "credit_tier_change";
            userMessage = "Your credit pack will change on " +
                    (effectiveDate != null ? effectiveDate.toLocalDate() : "soon");
        } else if (isBillingCycleChange) {
            changeType = "billing_cycle_change";
            userMessage = "Your billing cycle will change from " + currentBillingCycle + " to " + targetBillingCycle +
                    " on " + (effectiveDate != null ? effectiveDate.toLocalDate() : "soon");
        } else if (isDowngrade(currentPlanCode, targetPlanCode)) {
            changeType = "downgrade";
            userMessage = "Your plan will switch to " + targetPlanName + " on " +
                    (effectiveDate != null ? effectiveDate.toLocalDate() : "soon");
        } else {
            changeType = "billing_cycle_change";
            userMessage = "Your plan will switch to " + targetPlanName + " on " +
                    (effectiveDate != null ? effectiveDate.toLocalDate() : "soon");
        }

        return ScheduledChangeInfo.builder()
                .scheduleId(schedule.getId())
                .currentPlanCode(currentPlanCode)
                .currentPlanName(currentPlanName)
                .targetPlanCode(targetPlanCode)
                .targetPlanName(targetPlanName)
                .effectiveDate(effectiveDate)
                .changeType(changeType)
                .currentBillingCycle(currentBillingCycle)
                .targetBillingCycle(targetBillingCycle)
                .status(schedule.getStatus())
                .cancellable("not_started".equals(schedule.getStatus()) || "active".equals(schedule.getStatus()))
                .userMessage(userMessage)
                .currentCreditQty(isCreditTierChange ? currentCreditQty : null)
                .targetCreditQty(isCreditTierChange ? targetCreditQty : null)
                .build();
    }

    /**
     * Extracts the credit quantity from a subscription schedule phase.
     * Looks for items whose price ID is a known credit pack price.
     */
    private int extractCreditQuantityFromPhase(SubscriptionSchedule.Phase phase) {
        if (phase == null || phase.getItems() == null) return 0;
        for (var item : phase.getItems()) {
            String priceId = item.getPrice();
            boolean isCreditPack = priceId != null && priceCacheService.isCreditPackPrice(priceId);
            log.debug("[ScheduledChange] Phase item: priceId={}, qty={}, isCreditPack={}",
                    priceId, item.getQuantity(), isCreditPack);
            if (isCreditPack) {
                return item.getQuantity() != null ? item.getQuantity().intValue() : 0;
            }
        }
        return 0;
    }

    /**
     * Resolves a plan code from a Stripe price ID.
     */
    private String resolvePlanCodeFromPriceId(String priceId) {
        if (priceId == null) return null;
        
        var cachedPrices = priceCacheService.getAllCachedPrices();
        for (var entry : cachedPrices.entrySet()) {
            if (priceId.equals(entry.getValue())) {
                String key = entry.getKey();
                int underscoreIndex = key.lastIndexOf('_');
                if (underscoreIndex > 0) {
                    return key.substring(0, underscoreIndex);
                }
            }
        }
        return null;
    }

    /**
     * Checks if changing from currentPlan to targetPlan is a downgrade.
     */
    private boolean isDowngrade(String currentPlan, String targetPlan) {
        if (currentPlan == null || targetPlan == null) return false;
        long currentRank = planCacheService.getPlanOrder(currentPlan.toUpperCase()).orElse(0L);
        long targetRank = planCacheService.getPlanOrder(targetPlan.toUpperCase()).orElse(0L);
        return targetRank < currentRank;
    }

    /**
     * Gets the current billing cycle (monthly/yearly) for a subscription.
     */
    private String getCurrentBillingCycle(Subscription subscription) {
        if (subscription == null || subscription.getPrice() == null) {
            return "monthly"; // default
        }
        String priceId = subscription.getPrice().getProviderPriceId();
        if (priceId == null) {
            return "monthly";
        }
        return priceCacheService.getBillingCycleByPriceId(priceId).orElse("monthly");
    }

    /**
     * Gets the target billing cycle from a Stripe price ID.
     */
    private String getTargetBillingCycle(String priceId) {
        if (priceId == null) {
            return "monthly"; // default
        }
        return priceCacheService.getBillingCycleByPriceId(priceId).orElse("monthly");
    }
}
