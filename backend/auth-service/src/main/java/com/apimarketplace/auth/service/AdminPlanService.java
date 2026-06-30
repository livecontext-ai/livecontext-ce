package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Cloud-only admin service to grant a user a complimentary ("comp") subscription plan
 * without going through Stripe - the counterpart of {@link CreditService#grantCredits}
 * exposed by {@code AdminCreditController}, but for the PLAN TIER rather than credits.
 *
 * <p>Restricted to {@link #ALLOWED_PLAN_CODES} = {FREE, STARTER, PRO, TEAM}: the admin
 * UI only ever offers a tier change for capabilities (Team invites, per-plan resource
 * limits, storage quota). FREE is the "revert" target. ENTERPRISE tiers, PAYG and
 * credit-pack codes are intentionally NOT grantable here.
 *
 * <p><b>Credits are decoupled from the plan tier.</b> A comp plan does NOT grant the
 * plan's larger credit allowance - every comp account renews at the standard tier-0
 * base of 5,000 credits/month, exactly like a paying customer with no credit pack
 * (the "5k/month max" rule). The 5K base is granted immediately on assignment via the
 * existing renewal path ({@link CreditAttributionService#attributeOnRenewal}) and re-granted
 * monthly by {@link FreeSubscriptionRenewalScheduler} (which now renews all
 * {@code provider='internal'} subs, not just FREE). FREE reverts to its 1K plan-included
 * grant. The PAYG bucket is never touched by a plan change.
 *
 * <p><b>Stripe subs are never clobbered.</b> If the target user already has an active PAID
 * subscription ({@code provider != "internal"}), the grant is refused - the admin must
 * manage that through Stripe so the local row never diverges from Stripe state.
 *
 * <p>The plan tier is resolved LIVE from the subscription row on every request
 * (gateway {@code X-User-Plan} via {@code PlanResolutionService}), so the change takes
 * effect on the next request; the gateway user-resolution cache is busted best-effort so
 * it applies immediately rather than after the 5-min TTL. The bust goes through the SAME
 * {@link SubscriptionCacheBuster} the Stripe-webhook flow uses, so it fans out to the owner
 * AND every member of every org they own - a TEAM grant unlocks the workspace for the whole
 * team on their next request, not just the grantee.
 */
@Service
public class AdminPlanService {

    private static final Logger log = LoggerFactory.getLogger(AdminPlanService.class);

    /** The only plan codes an admin may grant from the admin-credits page. FREE = revert. */
    public static final Set<String> ALLOWED_PLAN_CODES = Set.of("FREE", "STARTER", "PRO", "TEAM");

    private static final String INTERNAL_PROVIDER = "internal";

    private final SubscriptionRepository subscriptionRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final CreditAttributionService creditAttributionService;
    private final PlanStorageQuotaSyncer quotaSyncer;
    private final SubscriptionCacheBuster subscriptionCacheBuster;
    private final OrganizationService organizationService;

    public AdminPlanService(SubscriptionRepository subscriptionRepository,
                            BillingCustomerRepository billingCustomerRepository,
                            PlanRepository planRepository,
                            UserRepository userRepository,
                            CreditAttributionService creditAttributionService,
                            PlanStorageQuotaSyncer quotaSyncer,
                            SubscriptionCacheBuster subscriptionCacheBuster,
                            OrganizationService organizationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.creditAttributionService = creditAttributionService;
        this.quotaSyncer = quotaSyncer;
        this.subscriptionCacheBuster = subscriptionCacheBuster;
        this.organizationService = organizationService;
    }

    /**
     * Assign a comp plan to {@code targetUserId}. Idempotent-friendly: re-assigning the
     * same plan re-anchors the monthly cycle and re-grants the base credits.
     *
     * @param targetUserId the recipient user id (already resolved by the controller)
     * @param rawPlanCode  the requested plan code (case-insensitive); must be in
     *                     {@link #ALLOWED_PLAN_CODES}
     * @param adminUserId  the acting admin (for log breadcrumbs only - audit is written
     *                     by the controller)
     * @return an {@link AssignPlanResult} describing success + the previous/new plan codes,
     *         or a structured failure
     */
    @Transactional
    public AssignPlanResult assignPlan(Long targetUserId, String rawPlanCode, Long adminUserId) {
        if (targetUserId == null) {
            return AssignPlanResult.fail("missing_target");
        }
        String planCode = rawPlanCode == null ? "" : rawPlanCode.trim().toUpperCase();
        if (!ALLOWED_PLAN_CODES.contains(planCode)) {
            return AssignPlanResult.fail("unsupported_plan");
        }

        Optional<Plan> planOpt = planRepository.findByCode(planCode);
        if (planOpt.isEmpty()) {
            log.error("Admin {} requested plan {} which is not seeded in the plan table",
                    adminUserId, planCode);
            return AssignPlanResult.fail("plan_not_found");
        }
        Plan plan = planOpt.get();

        Optional<User> userOpt = userRepository.findById(targetUserId);
        if (userOpt.isEmpty()) {
            return AssignPlanResult.fail("user_not_found");
        }
        User user = userOpt.get();

        // Take the most-recent active subscription under a write lock. If it is a real
        // Stripe subscription, refuse - comp is only for internal/free accounts and we
        // must never desync the local row from Stripe.
        Optional<Subscription> activeOpt = subscriptionRepository.findActiveByUserIdForUpdate(targetUserId);
        Subscription sub;
        if (activeOpt.isPresent()) {
            sub = activeOpt.get();
            if (!INTERNAL_PROVIDER.equalsIgnoreCase(sub.getProvider())) {
                log.warn("Admin {} attempted comp-plan grant to user {} who has an active paid ({}) subscription - refused",
                        adminUserId, targetUserId, sub.getProvider());
                return AssignPlanResult.fail("has_paid_subscription");
            }
        } else {
            // No active sub (e.g. a user that never resolved): bootstrap an internal one,
            // mirroring UserResolutionService.ensureFreeSubscription.
            BillingCustomer billingCustomer = billingCustomerRepository.findByUserId(targetUserId)
                    .orElseGet(() -> billingCustomerRepository.save(new BillingCustomer(user, INTERNAL_PROVIDER)));
            sub = new Subscription();
            sub.setBillingCustomer(billingCustomer);
        }

        String previousPlanCode = sub.getPlan() != null ? sub.getPlan().getCode() : null;

        // Anchor a fresh monthly cycle for the comp plan. providerSubscriptionId stays null
        // (internal), creditQuantity=0 so the base resolves to the tier-0 5K pack.
        LocalDateTime now = LocalDateTime.now();
        sub.setPlan(plan);
        sub.setProvider(INTERNAL_PROVIDER);
        sub.setPrice(null);
        sub.setCadence("monthly");
        sub.setStatus("active");
        sub.setCreditQuantity(0);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plusMonths(1));
        sub.setCancelAtPeriodEnd(false);
        sub.setUpdatedAt(now);
        if (sub.getCreatedAt() == null) {
            sub.setCreatedAt(now);
        }
        Subscription saved = subscriptionRepository.save(sub);

        // Sync the storage quota to the new plan's allowance (after commit, same as the
        // Stripe plan-change path).
        quotaSyncer.syncAfterCommit(targetUserId, plan);

        // V311: reconcile owned workspaces to the new plan's workspace cap (same as the
        // Stripe plan-change path). Downgrade pauses the most-recent excess workspaces;
        // upgrade un-pauses up to the new cap. Idempotent; never fail the grant on a glitch.
        try {
            organizationService.reconcileWorkspacePauseState(targetUserId);
        } catch (Exception e) {
            log.error("Workspace pause reconcile failed for user {} on admin plan grant: {}",
                    targetUserId, e.getMessage(), e);
        }

        // Grant the base credits for the freshly-anchored cycle: resets the sub bucket to 0
        // then grants the plan base (comp Starter/Pro/Team -> 5K tier-0, FREE -> 1K). The
        // PAYG bucket is left untouched. Idempotent via sourceIds derived from periodStart.
        creditAttributionService.attributeOnRenewal(targetUserId, saved);

        // Best-effort: bust the gateway user-resolution cache so the new tier applies on the
        // next request instead of after the 5-min TTL. Same fan-out pipe as the Stripe flow -
        // busts the grantee AND every member of every org they own, so a TEAM grant unlocks
        // the workspace for the whole team immediately. The plan tier itself is resolved live.
        subscriptionCacheBuster.fanOutForOwner(targetUserId, "admin.plan.grant");

        log.info("Admin {} assigned comp plan {} -> {} to user {} (subId={})",
                adminUserId, previousPlanCode, plan.getCode(), targetUserId, saved.getId());
        return AssignPlanResult.ok(previousPlanCode, plan.getCode());
    }

    /**
     * Outcome of {@link #assignPlan}. On success carries the previous + new plan codes;
     * on failure carries a stable {@code error} token the controller maps to an HTTP status.
     */
    public record AssignPlanResult(boolean success, String error, String previousPlanCode, String newPlanCode) {
        public static AssignPlanResult ok(String previousPlanCode, String newPlanCode) {
            return new AssignPlanResult(true, null, previousPlanCode, newPlanCode);
        }

        public static AssignPlanResult fail(String error) {
            return new AssignPlanResult(false, error, null, null);
        }
    }
}
