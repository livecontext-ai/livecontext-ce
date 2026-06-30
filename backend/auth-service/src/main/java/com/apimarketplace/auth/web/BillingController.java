// src/main/java/com/apimarketplace/auth/web/BillingController.java
package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.enums.PlanCode;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.util.NonceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.apimarketplace.auth.domain.dto.BillingInvoiceDto;
import com.apimarketplace.auth.domain.dto.PlanChangeRequest;
import com.apimarketplace.auth.domain.dto.PlanChangeResult;
import com.apimarketplace.auth.domain.dto.ScheduledChangeInfo;
import com.apimarketplace.auth.service.StripeScheduleService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.StripeCollection;
import com.stripe.param.InvoiceListParams;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/billing")
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class BillingController {

    private static final Logger logger = LoggerFactory.getLogger(BillingController.class);

    @Autowired private StripeBillingService stripeBillingService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PriceRepository priceRepository;
    @Autowired private BillingEventRepository billingEventRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.apimarketplace.auth.service.PlanCacheService planCacheService;

    // On garde ces repos ici (utilises ailleurs), mais on evite les appels sensibles dans finalizeCheckout
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingCustomerRepository billingCustomerRepository;

    @Autowired private PriceCacheService priceCacheService;

    @Autowired
    private com.apimarketplace.auth.service.SubscriptionService subscriptionService;
    
    @Autowired
    private NonceUtil nonceUtil;
    
    @Autowired
    private StripeScheduleService stripeScheduleService;
    
    @Autowired
    private StripeClient stripeClient; // SDK 31+ best practice: use injected client

    @Autowired
    private com.apimarketplace.auth.service.PlanStorageQuotaSyncer quotaSyncer;

    @Autowired
    private com.apimarketplace.auth.repository.OrganizationMemberRepository orgMemberRepository;

    @Autowired
    private com.apimarketplace.auth.service.RewardService rewardService;

    /**
     * Extrait l'ID utilisateur depuis les headers du gateway
     * @param request La requete HTTP
     * @return L'ID utilisateur ou null si invalide
     */
    private Long extractUserId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        if (userIdHeader == null) {
            logger.warn("Header X-User-ID manquant dans la requete");
            return null;
        }

        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            logger.error("Format invalide pour X-User-ID: {}", userIdHeader);
            return null;
        }
    }

    /**
     * PR5 - block billing mutations from members of an org they don't own.
     * The gateway injects X-Organization-Role from the active workspace
     * (PR0.5b). Only OWNER of the active workspace can pay. To manage
     * their own subscription, the user must switch back to their personal
     * organization (always OWNER there). Returns null if the request IS
     * authorized, or a 403 response if not.
     *
     * <p>Personal-org safety net: when the active org IS the user's personal
     * org, role is OWNER by construction (auto-created at signup), so this
     * guard never blocks the user from managing their own subscription.
     */
    private ResponseEntity<Map<String, Object>> requireActiveOrgOwner(HttpServletRequest request) {
        String role = request.getHeader("X-Organization-Role");
        if (role == null || !"OWNER".equalsIgnoreCase(role.trim())) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Forbidden",
                    "message", "Only the owner of the active workspace can manage billing. "
                            + "Switch to your personal organization to subscribe.",
                    "code", "NOT_WORKSPACE_OWNER"
            ));
        }
        return null;
    }

    /**
     * POST /api/billing/redeem - redeem a reward code (promo, referral, or partner)
     * for the authenticated user. The benefit is per-user, so ANY signed-in user may
     * redeem (no workspace-owner gate). A redeem-time benefit (promo free-node) is
     * granted immediately; a conversion-gated one (referral) is attributed and
     * granted later when the redeemer's first paid subscription is captured.
     *
     * <p>Body: {@code { "code": "ABCD2345" }}. Status codes: 200 immediate benefit,
     * 202 attributed pending conversion (or held over the soft cap), 404 INVALID_CODE,
     * 409 NOT_REDEEMABLE / ALREADY_REDEEMED / EXHAUSTED / SELF_REFERRAL / ALREADY_PAID.
     */
    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeemRewardCode(
            @RequestBody(required = false) Map<String, String> body, HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized", "code", "NO_USER"));
        }
        String code = body == null ? null : body.get("code");
        try {
            com.apimarketplace.auth.service.RewardService.RedeemResult result = rewardService.redeem(userId, code);
            switch (result.status()) {
                case SUCCESS -> {
                    var r = result.redemption();
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("success", true);
                    ok.put("code", "REDEEMED");
                    ok.put("status", r.getStatus().name());
                    ok.put("benefitType", r.getBenefitType());
                    ok.put("benefitUntil", r.getBenefitUntil() != null ? r.getBenefitUntil().toString() : null);
                    ok.put("freeCreditsCap", r.getFreeCreditsCap());
                    logger.info("Reward code redeemed by user {} (status {})", userId, r.getStatus());
                    return ResponseEntity.ok(ok);
                }
                case PENDING_CONVERSION, TRACK_ONLY -> {
                    var r = result.redemption();
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("success", true);
                    ok.put("code", result.status().name());
                    ok.put("status", r != null ? r.getStatus().name() : null);
                    logger.info("Reward code attributed for user {} (status {})", userId, result.status());
                    return ResponseEntity.status(202).body(ok);
                }
                case UNKNOWN_CODE -> {
                    return rewardError(404, "INVALID_CODE", "This code doesn't exist.");
                }
                case NOT_REDEEMABLE -> {
                    return rewardError(409, "NOT_REDEEMABLE", "This code isn't available right now.");
                }
                case ALREADY_REDEEMED -> {
                    return rewardError(409, "ALREADY_REDEEMED", "You've already redeemed a code.");
                }
                case EXHAUSTED -> {
                    return rewardError(409, "EXHAUSTED", "This code has reached its redemption limit.");
                }
                case SELF_REFERRAL -> {
                    return rewardError(409, "SELF_REFERRAL", "You can't redeem your own referral code.");
                }
                case ALREADY_PAID -> {
                    return rewardError(409, "ALREADY_PAID", "This code is for new subscriptions only.");
                }
                default -> {
                    return rewardError(400, "REDEEM_FAILED", "Could not redeem this code.");
                }
            }
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Rare concurrent double-redeem by the same user trips a unique constraint.
            return rewardError(409, "ALREADY_REDEEMED", "You've already redeemed a code.");
        }
    }

    private ResponseEntity<Map<String, Object>> rewardError(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }

    /**
     * GET /api/billing/rewards - active (non-expired) free-node benefits for the
     * authenticated user, so the UI can surface how much of the cap is left.
     */
    @GetMapping("/rewards")
    public ResponseEntity<Map<String, Object>> rewards(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized", "code", "NO_USER"));
        }
        List<Map<String, Object>> benefits = rewardService.getActiveBenefits(userId).stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("benefitType", r.getBenefitType());
                    m.put("benefitUntil", r.getBenefitUntil() != null ? r.getBenefitUntil().toString() : null);
                    m.put("freeCreditsCap", r.getFreeCreditsCap());
                    m.put("freeCreditsUsed", r.getFreeCreditsUsed());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(Map.of("benefits", benefits));
    }

    /**
     * GET /api/billing/me/invite - the caller's personal referral code (minted on
     * first access) plus their invite progress (redeemed, pending, in-hold, and
     * rewarded counts plus credits earned).
     */
    @GetMapping("/me/invite")
    public ResponseEntity<Map<String, Object>> myInvite(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized", "code", "NO_USER"));
        }
        var stats = rewardService.getInviteStats(userId);
        Map<String, Object> bodyOut = new HashMap<>();
        bodyOut.put("code", stats.code());
        bodyOut.put("redeemedCount", stats.redeemedCount());
        bodyOut.put("pendingCount", stats.pendingCount());
        bodyOut.put("inHoldCount", stats.inHoldCount());
        bodyOut.put("rewardedCount", stats.rewardedCount());
        bodyOut.put("creditsEarned", stats.creditsEarned());
        bodyOut.put("softCapLimit", stats.softCapLimit());
        bodyOut.put("rewardCredits", stats.rewardCredits());
        return ResponseEntity.ok(bodyOut);
    }

    /**
     * POST /api/billing/checkout - Cree une session de checkout Stripe
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> createCheckoutSession(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) throws StripeException {

        try {
            // Recuperer l'ID utilisateur depuis les headers du gateway
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }
            // PR5: only OWNER of the active workspace can subscribe.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            logger.info("Creation de session checkout pour userId={}", userId);

            String planCode = request.get("planCode");
            if (planCode == null || planCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "planCode est requis"));
            }

            String billingCycle = request.get("billingCycle");
            if (billingCycle == null || billingCycle.trim().isEmpty()) {
                billingCycle = "monthly"; // Valeur par defaut
            }

            // Normaliser le code de plan
            String normalizedPlanCode = PlanCode.normalize(planCode);
            if (normalizedPlanCode == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Code de plan invalide"));
            }

            // Verifier si le plan est valide en utilisant les codes de la DB
            Map<String, Plan> availablePlans = planCacheService.getAllPlans();
            Set<String> availablePlanCodes = availablePlans.keySet();

            if (!PlanCode.isValid(normalizedPlanCode, availablePlanCodes)) {
                String availablePlanCodesList = String.join(", ", availablePlanCodes);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Plan non supporte: " + planCode + ". Plans disponibles: " + availablePlanCodesList
                                                              ));
            }


            Map<String, Object> response = new HashMap<>();

            if ("FREE".equals(normalizedPlanCode)) {
                try {
                    var userOpt = userRepository.findById(userId);
                    if (userOpt.isEmpty()) {
                        throw new IllegalArgumentException("Utilisateur non trouve");
                    }

                    // Enregistrer l'evenement de selection du plan gratuit
                    ObjectNode eventPayload = objectMapper.createObjectNode();
                    eventPayload.put("userId", userId);
                    eventPayload.put("planCode", planCode);
                    eventPayload.put("action", "free_plan_selected");

                    BillingEvent freePlanEvent = new BillingEvent(
                            "internal",
                            "free_plan_" + userId + "_" + System.currentTimeMillis(),
                            "plan.selected",
                            eventPayload
                    );
                    billingEventRepository.save(freePlanEvent);

                } catch (Exception e) {
                    logger.error("Erreur lors de la creation de l'abonnement gratuit: {}", e.getMessage());
                    throw new IllegalArgumentException("Erreur lors de la creation de l'abonnement gratuit");
                }
            } else {
                try {
                    // Parse credit tier index
                    int creditTierIndex = 0;
                    if (request.containsKey("creditTierIndex")) {
                        try {
                            creditTierIndex = Integer.parseInt(request.get("creditTierIndex").toString());
                        } catch (NumberFormatException e) {
                            return ResponseEntity.badRequest().body(Map.of("error", "Invalid creditTierIndex"));
                        }
                    }

                    String checkoutUrl = stripeBillingService.createCheckoutSession(userId, normalizedPlanCode, billingCycle, creditTierIndex);
                    response.put("url", checkoutUrl);
                    response.put("planCode", planCode);
                    response.put("isFreePlan", false);
                    logger.info("Session de checkout creee pour l'utilisateur {} (plan: {})", userId, planCode);

                    // Generer un nonce pour cet utilisateur (pour reference dans les logs)
                    String nonce = nonceUtil.generateNonce(userId);
                    logger.debug("Generated nonce for checkout session: {} -> {}", userId, nonce);

                    // Enregistrer l'evenement de creation de session de checkout
                    ObjectNode checkoutEventPayload = objectMapper.createObjectNode();
                    checkoutEventPayload.put("userId", userId);
                    checkoutEventPayload.put("planCode", planCode);
                    checkoutEventPayload.put("checkoutUrl", checkoutUrl);
                    checkoutEventPayload.put("nonce", nonce);
                    checkoutEventPayload.put("action", "checkout_session_created");

                    BillingEvent checkoutEvent = new BillingEvent(
                            "stripe",
                            "checkout_" + userId + "_" + System.currentTimeMillis(),
                            "checkout.session.created",
                            checkoutEventPayload
                    );
                    billingEventRepository.save(checkoutEvent);
                } catch (IllegalArgumentException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Plan non valide")) {
                        logger.error("Plan Stripe non configure: {}. Verifiez la configuration des prix.", planCode);
                        throw new IllegalArgumentException("Plan non configure dans Stripe: " + planCode + ". Contactez l'administrateur.");
                    }
                    throw e;
                }
            }

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Erreur de validation lors de la creation du checkout: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erreur lors de la creation de la session de checkout", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur interne du serveur"));
        }
    }

    /**
     * POST /api/billing/portal - Cree une session du portail de facturation
     */
    @PostMapping("/portal")
    public ResponseEntity<Map<String, Object>> createBillingPortalSession(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) throws StripeException {

        try {
            // Recuperer l'ID utilisateur depuis les headers du gateway
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }
            // PR5: only OWNER of the active workspace can open the billing portal.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            logger.info("Creation de session portal pour userId={}", userId);

            String returnUrl = request.get("returnUrl");
            String portalUrl = stripeBillingService.createBillingPortalSession(userId, returnUrl);

            Map<String, Object> response = new HashMap<>();
            response.put("url", portalUrl);

            logger.info("Session du portail de facturation creee pour l'utilisateur {}", userId);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Erreur de validation lors de la creation du portail: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erreur lors de la creation de la session du portail", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur interne du serveur"));
        }
    }

    /**
     * V250 - GET /api/billing/payg-tiers - list the three PAYG top-up tiers
     * with their credit grants, Stripe amount and {@code configured} flag.
     *
     * <p>Read-only, no org-owner gate (the tier catalog is public - only the
     * actual checkout requires the workspace-owner guard). Frontend uses the
     * {@code configured=false} state to render "PAYG bientôt disponible"
     * without firing a request that would 503 with {@code PAYG_PRICE_UNCONFIGURED}.
     */
    @GetMapping("/payg-tiers")
    public ResponseEntity<Map<String, Object>> getPaygTiers() {
        try {
            var tiers = stripeBillingService.getPaygTiers();
            boolean anyConfigured = tiers.stream().anyMatch(StripeBillingService.PaygTierView::configured);
            return ResponseEntity.ok(Map.of(
                    "tiers", tiers,
                    "configured", anyConfigured));
        } catch (Exception e) {
            logger.error("PAYG tiers fetch failed", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * V250/PR3 - POST /api/billing/payg-checkout - Create a Stripe one-time
     * checkout session for PAYG credit top-up (tier=small/medium/large).
     *
     * <p>Gated by {@link #requireActiveOrgOwner} like every other billing
     * mutation. The PAYG bucket lives on {@code subscription.payg_remaining_credits}
     * of the active-workspace OWNER - a TEAM member topping up while in a TEAM
     * workspace would deposit money into their PERSONAL bucket, which
     * {@code CreditService.resolvePayer} never consults for TEAM-workspace
     * consumption. The result would be paid-but-unused PAYG dollars stranded
     * on the member's account. The guard forces the member to switch to their
     * personal workspace first, so the funded bucket is the one their future
     * personal consumption will actually debit.
     *
     * <p>On success the frontend redirects to the returned Stripe URL; on
     * payment completion, {@code WebhookController.handleCheckoutCompleted}
     * branches on {@code metadata.kind="payg_topup"} and grants credits to
     * the user's {@code payg_remaining_credits} bucket.
     *
     * <p>Request body: {@code {"tier": "small" | "medium" | "large"}}.
     */
    @PostMapping("/payg-checkout")
    public ResponseEntity<Map<String, Object>> createPaygCheckout(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }

            // Mirror every other billing mutation. Without this gate a TEAM
            // member could pay Stripe and watch the credits land on a bucket
            // their future LLM consumption never debits - silent dead money.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) {
                return ownerCheck;
            }

            String tier = request.get("tier");
            if (tier == null || tier.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "tier required (small|medium|large)"));
            }

            logger.info("PAYG checkout request: userId={}, tier={}", userId, tier);

            String checkoutUrl = stripeBillingService.createPaygCheckoutSession(userId, tier);

            Map<String, Object> response = new HashMap<>();
            response.put("url", checkoutUrl);
            response.put("tier", tier);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("PAYG checkout validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Thrown when a PAYG price is not wired to Stripe yet - ops issue
            logger.error("PAYG checkout configuration error: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "PAYG checkout is not yet available - please contact support",
                    "error_code", "PAYG_PRICE_UNCONFIGURED"));
        } catch (Exception e) {
            logger.error("PAYG checkout failed", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * POST /api/billing/cancel-subscription - Cancel subscription at period end
     */
    @PostMapping("/cancel-subscription")
    public ResponseEntity<Map<String, Object>> cancelSubscription(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) throws StripeException {
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can cancel the subscription.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            String reason = request.get("reason");
            if (reason == null || reason.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "reason is required"));
            }
            String feedback = request.get("feedback");

            logger.info("Cancel subscription request for userId={}, reason={}", userId, reason);

            // Call service to cancel at period end
            Map<String, Object> result = stripeBillingService.cancelSubscriptionAtPeriodEnd(userId, reason, feedback);

            // Log cancellation event
            ObjectNode eventPayload = objectMapper.createObjectNode();
            eventPayload.put("userId", userId);
            eventPayload.put("reason", reason);
            if (feedback != null) eventPayload.put("feedback", feedback);
            eventPayload.put("action", "subscription_cancel_scheduled");

            BillingEvent cancelEvent = new BillingEvent(
                    "internal",
                    "cancel_" + userId + "_" + System.currentTimeMillis(),
                    "subscription.cancel_scheduled",
                    eventPayload
            );
            billingEventRepository.save(cancelEvent);

            return ResponseEntity.ok(result);

        } catch (IllegalStateException e) {
            logger.warn("Cancel subscription error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error cancelling subscription", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * POST /api/billing/reactivate-subscription - Reactivate a subscription scheduled for cancellation
     */
    @PostMapping("/reactivate-subscription")
    public ResponseEntity<Map<String, Object>> reactivateSubscription(
            HttpServletRequest httpRequest) throws StripeException {
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can reactivate.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            logger.info("Reactivate subscription request for userId={}", userId);

            Map<String, Object> result = stripeBillingService.reactivateSubscription(userId);

            // Log reactivation event
            ObjectNode eventPayload = objectMapper.createObjectNode();
            eventPayload.put("userId", userId);
            eventPayload.put("action", "subscription_reactivated");

            BillingEvent reactivateEvent = new BillingEvent(
                    "internal",
                    "reactivate_" + userId + "_" + System.currentTimeMillis(),
                    "subscription.reactivated",
                    eventPayload
            );
            billingEventRepository.save(reactivateEvent);

            return ResponseEntity.ok(result);

        } catch (IllegalStateException e) {
            logger.warn("Reactivate subscription error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error reactivating subscription", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * S'assure que l'utilisateur existe en base (peut creer l'utilisateur si necessaire)
     * Utilise REQUIRES_NEW pour creer une nouvelle transaction separee
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long ensureUserExists(HttpServletRequest request) {
        return extractUserId(request);
    }


    /**
     * GET /api/billing/me - Recupere l'etat d'abonnement de l'utilisateur
     */
    @Transactional(readOnly = true)
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentBillingStatus(
            HttpServletRequest request) {

        try {
            // Recuperer l'ID utilisateur depuis les headers du gateway
            String userIdHeader = request.getHeader("X-User-ID");
            if (userIdHeader == null) {
                logger.warn("Header X-User-ID manquant dans la requete");
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }

            Long userId;
            try {
                userId = Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                logger.error("Format invalide pour X-User-ID: {}", userIdHeader);
                return ResponseEntity.status(401).body(Map.of("error", "Format utilisateur invalide"));
            }

            logger.info("Utilisateur authentifie via gateway - User ID: {}", userId);

            Map<String, Object> response = new HashMap<>();
            response.put("userId", userId);

            // PR7 audit B fix: surface the gateway's resolved X-User-Plan
            // (= active workspace tier when mode=active-org) so the FE
            // sidebar plan badge can prefer it over the per-user
            // subscription.planCode. Without this, a TEAM-org-member with
            // FREE personal subscription saw "FREE" on the badge while the
            // gateway gated capabilities on TEAM - visible UX inconsistency.
            String activeOrgPlanCode = request.getHeader("X-User-Plan");
            if (activeOrgPlanCode != null && !activeOrgPlanCode.isBlank()) {
                response.put("activeOrgPlanCode", activeOrgPlanCode);
            }

            try {
                var statuses = List.of("active", "trialing", "past_due", "incomplete");
                logger.info("Recherche d'abonnement pour userId={} avec statuts: {}", userId, statuses);

                Optional<Subscription> activeSubscription =
                        subscriptionRepository.findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(userId, statuses);

                logger.info("Resultat de la recherche: {}", activeSubscription.isPresent() ? "trouve" : "non trouve");
                activeSubscription.ifPresent(s ->
                                                     logger.info("Abonnement trouve: id={}, status={}, plan={}",
                                                                 s.getId(), s.getStatus(), s.getPlan() != null ? s.getPlan().getCode() : "null")
                                            );

                if (activeSubscription.isPresent()) {
                    Subscription subscription = activeSubscription.get();
                    Plan plan = subscription.getPlan();
                    String cadence = "monthly"; // Default

                    // Recuperer la cadence depuis le price ou depuis l'abonnement
                    if (subscription.getPrice() != null) {
                        cadence = subscription.getPrice().getCadence();
                    } else {
                        cadence = subscription.getCadence();
                    }

                    Map<String, Object> subMap = new HashMap<>();
                    subMap.put("id", subscription.getId());
                    subMap.put("status", subscription.getStatus());
                    subMap.put("planCode", plan != null ? plan.getCode() : "FREE");
                    subMap.put("planName", plan != null ? plan.getName() : "Gratuit");
                    subMap.put("cadence", cadence);
                    subMap.put("currentPeriodStart", subscription.getCurrentPeriodStart());
                    subMap.put("currentPeriodEnd", subscription.getCurrentPeriodEnd());
                    subMap.put("cancelAtPeriodEnd", subscription.getCancelAtPeriodEnd());
                    subMap.put("provider", subscription.getProvider());
                    subMap.put("providerSubscriptionId", subscription.getProviderSubscriptionId());
                    String planCode = plan != null ? plan.getCode() : "FREE";
                    subMap.put("creditQuantity", subscription.getCreditQuantity() != null ? subscription.getCreditQuantity() : 0);
                    subMap.put("creditTierIndex", CreditTierConstants.resolveTierIndex(
                        subscription.getCreditQuantity() != null ? subscription.getCreditQuantity() : 0,
                        planCode));

                    response.put("subscription", subMap);

                    response.put("hasActiveSubscription", true);
                    response.put("canUpgrade", !"ENTERPRISE".equals(plan != null ? plan.getCode() : "FREE"));

                } else {
                    response.put("subscription", null);
                    response.put("hasActiveSubscription", false);
                    response.put("canUpgrade", true);
                    response.put("status", "no_subscription");
                    response.put("plan", "free");
                }

            } catch (Exception e) {
                logger.warn("Erreur lors de la recuperation du statut d'abonnement: {}", e.getMessage());
                response.put("subscription", null);
                response.put("status", "error");
                response.put("error", "Impossible de recuperer le statut d'abonnement");
                response.put("hasActiveSubscription", false);
                response.put("canUpgrade", false);
            }

            return ResponseEntity.ok()
                                 .header("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate")
                                 .header("Pragma", "no-cache")
                                 .header("Expires", "0")
                                 .body(response);

        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation du statut de facturation", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur interne du serveur"));
        }
    }

    @GetMapping("/checkout/finalize")
    public ResponseEntity<Map<String, Object>> finalizeCheckout(@RequestParam("session_id") String sessionId) {
        try {
            logger.info("Verification de l'abonnement pour la session: {}", sessionId);

            var session = stripeClient.checkout().sessions().retrieve(sessionId);
            if (!"subscription".equals(session.getMode())) {
                return ResponseEntity.badRequest().body(Map.of("state", "error", "error", "Session non SUBSCRIPTION"));
            }

            String providerSubId = session.getSubscription();

            // 1) Verifier si l'abonnement existe deja en DB (cree par webhook)
            if (providerSubId != null) {
                if (subscriptionService.existsByProviderSubscriptionId(providerSubId)) {
                    logger.info("Abonnement trouve en DB (cree par webhook): {}", providerSubId);

                    // Recuperer les details de l'abonnement pour la reponse complete
                    try {
                        var subscription = subscriptionService.findByProviderSubscriptionId(providerSubId);
                        if (subscription.isPresent()) {
                            var sub = subscription.get();
                            return ResponseEntity.ok(Map.of(
                                    "state", "provisioned",
                                    "message", "Abonnement active par webhook",
                                    "subscriptionId", providerSubId,
                                    "planId", sub.getPlan().getId(),
                                    "status", sub.getStatus(),
                                    "currentPeriodStart", sub.getCurrentPeriodStart() != null ? sub.getCurrentPeriodStart().toString() : null,
                                    "currentPeriodEnd", sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : null
                                                           ));
                        }
                    } catch (Exception e) {
                        logger.warn("Erreur lors de la recuperation des details de l'abonnement: {}", e.getMessage());
                    }

                    // Fallback si erreur de recuperation
                    return ResponseEntity.ok(Map.of(
                            "state", "provisioned",
                            "message", "Abonnement active par webhook",
                            "subscriptionId", providerSubId
                                                   ));
                }
            }

            // 2) Si pas d'ID sub, chercher via customer (webhook peut avoir cree l'abonnement)
            if (providerSubId == null && session.getCustomer() != null) {
                try {
                    var list = stripeClient.subscriptions().list(
                            com.stripe.param.SubscriptionListParams.builder()
                                                                   .setCustomer(session.getCustomer())
                                                                   .setLimit(1L)
                                                                   .build()
                                                                 );
                    if (list != null && !list.getData().isEmpty()) {
                        providerSubId = list.getData().get(0).getId();
                        // Verifier si ce nouvel abonnement est deja en DB (cree par webhook)
                        if (subscriptionService.existsByProviderSubscriptionId(providerSubId)) {
                            logger.info("Abonnement trouve via customer et cree par webhook: {}", providerSubId);

                            // Recuperer les details de l'abonnement pour la reponse complete
                            try {
                                var subscription = subscriptionService.findByProviderSubscriptionId(providerSubId);
                                if (subscription.isPresent()) {
                                    var sub = subscription.get();
                                    return ResponseEntity.ok(Map.of(
                                            "state", "provisioned",
                                            "message", "Abonnement active par webhook",
                                            "subscriptionId", providerSubId,
                                            "planId", sub.getPlan().getId(),
                                            "status", sub.getStatus(),
                                            "currentPeriodStart", sub.getCurrentPeriodStart() != null ? sub.getCurrentPeriodStart().toString() : null,
                                            "currentPeriodEnd", sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : null
                                                                   ));
                                }
                            } catch (Exception e) {
                                logger.warn("Erreur lors de la recuperation des details de l'abonnement: {}", e.getMessage());
                            }

                            // Fallback si erreur de recuperation
                            return ResponseEntity.ok(Map.of(
                                    "state", "provisioned",
                                    "message", "Abonnement active par webhook",
                                    "subscriptionId", providerSubId
                                                           ));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Erreur lors de la recherche d'abonnement via customer: {}", e.getMessage());
                }
            }

            // 3) Verifier le statut de la session
            String sessionStatus = session.getStatus();
            if ("complete".equals(sessionStatus)) {
                // Session complete mais abonnement pas encore cree par webhook
                logger.info("Session complete, en attente du webhook pour la session: {}", sessionId);
                return ResponseEntity.status(202).body(Map.of(
                        "state", "processing",
                        "message", "En attente de l'activation par webhook",
                        "retry_after", 2
                                                             ));
            } else if ("expired".equals(sessionStatus)) {
                return ResponseEntity.badRequest().body(Map.of("state", "error", "error", "Session expiree"));
            } else {
                // Session en cours (open, incomplete, etc.)
                logger.info("Session en cours ({}), en attente de finalisation: {}", sessionStatus, sessionId);
                return ResponseEntity.status(202).body(Map.of(
                        "state", "processing",
                        "message", "Session en cours de traitement",
                        "retry_after", 2
                                                             ));
            }

        } catch (Exception e) {
            logger.error("Erreur lors de la verification de l'abonnement: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur interne du serveur", "state", "error"));
        }
    }

    private String resolvePlanCodeFromPriceId(String priceId) {
        if (priceId == null || priceId.isBlank()) return null;

        // Utiliser le cache des prix pour resoudre le planCode
        Map<String, String> cachedPrices = priceCacheService.getAllCachedPrices();
        if (cachedPrices == null || cachedPrices.isEmpty()) {
            logger.warn("Cache des prix vide, impossible de resoudre le planCode pour priceId: {}", priceId);
            return null;
        }

        // Chercher dans le cache: format "PLANCODE_CADENCE" -> "price_id"
        for (var entry : cachedPrices.entrySet()) {
            if (priceId.equals(entry.getValue())) {
                String key = entry.getKey();
                // Extraire le planCode (partie avant le "_")
                int underscoreIndex = key.lastIndexOf('_');
                if (underscoreIndex > 0) {
                    String planCode = key.substring(0, underscoreIndex);
                    logger.debug("PlanCode resolu: {} -> {}", priceId, planCode);
                    return planCode;
                }
            }
        }

        logger.warn("PlanCode non trouve pour priceId: {} dans le cache des prix", priceId);
        return null;
    }

    /**
     * GET /api/billing/checkout/success - Endpoint de confirmation de checkout (no-op pour UX)
     */
    @GetMapping("/checkout/success")
    public ResponseEntity<Void> onCheckoutSuccess(@RequestParam("session_id") String sessionId) {
        try {
            logger.info("Checkout success confirme pour la session: {}", sessionId);
            try {
                com.stripe.model.checkout.Session session = stripeClient.checkout().sessions().retrieve(sessionId);
                logger.info("Session Stripe validee: {} (status: {})", sessionId, session.getStatus());
            } catch (Exception e) {
                logger.warn("Session Stripe non trouvee ou invalide: {}", sessionId);
            }
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Erreur lors de la confirmation de checkout: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }

    /**
     * GET /api/billing/config - Test de la configuration Stripe (pour debug)
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getStripeConfig() {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("stripeConfigured", stripeBillingService != null);
            config.put("webhookEndpoint", "/api/webhooks/stripe");
            config.put("webhookConfigured", true);
            config.put("checkoutEndpoint", "/api/billing/checkout");
            config.put("portalEndpoint", "/api/billing/portal");

            try {
                config.put("stripeStatus", "OK");
                if (stripeBillingService != null) {
                    config.put("stripePropertiesLoaded", true);
                    try {
                        var debugInfo = stripeBillingService.getDebugInfo();
                        config.put("stripeDebug", debugInfo);
                    } catch (Exception e) {
                        config.put("stripeDebugError", e.getMessage());
                    }
                } else {
                    config.put("stripePropertiesLoaded", false);
                }
            } catch (Exception e) {
                config.put("stripeStatus", "ERROR");
                config.put("stripeError", e.getMessage());
            }

            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation de la configuration", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur interne du serveur"));
        }
    }

    // Les webhooks Stripe sont maintenant geres par WebhookController

    /**
     * GET /api/billing/plans - Recupere tous les plans avec leurs prix depuis le cache
     */
    @GetMapping("/plans")
    public ResponseEntity<Map<String, Object>> getPlans() {
        try {
            // Utiliser le cache au lieu des repositories directs
            Map<String, Plan> plans = planCacheService.getAllPlans();
            Map<String, Price> prices = planCacheService.getAllPrices();
            Map<String, Object> response = new HashMap<>();

            for (Map.Entry<String, Plan> entry : plans.entrySet()) {
                String planCode = entry.getKey();
                Plan plan = entry.getValue();

                Map<String, Object> planData = new HashMap<>();
                planData.put("id", plan.getId());
                planData.put("code", plan.getCode());
                planData.put("name", plan.getName());
                planData.put("description", plan.getDescription());
                planData.put("includedStorageBytes", plan.getIncludedStorageBytes());
                planData.put("includedToolCredits", plan.getIncludedToolCredits());
                planData.put("includedLlmTokens", plan.getIncludedLlmTokens());
                planData.put("maxMembers", plan.getMaxMembers());

                // Recuperer les prix pour ce plan depuis le cache
                Map<String, Object> pricesData = new HashMap<>();
                for (Map.Entry<String, Price> priceEntry : prices.entrySet()) {
                    String priceKey = priceEntry.getKey();
                    Price price = priceEntry.getValue();

                    // Verifier si ce prix appartient a ce plan
                    if (priceKey.startsWith(planCode + "_")) {
                        Map<String, Object> priceData = new HashMap<>();
                        priceData.put("amount_cents", price.getAmountCents());
                        priceData.put("amount_dollars", price.getAmountCents() / 100.0); // Conversion en dollars
                        priceData.put("currency", price.getCurrency());
                        // provider_price_id n'est pas expose au client pour des raisons de securite

                        // Extraire la cadence de la cle (planCode_cadence)
                        String cadence = priceKey.substring(planCode.length() + 1);
                        pricesData.put(cadence, priceData);
                    }
                }

                planData.put("prices", pricesData);
                response.put(plan.getCode(), planData);
            }

            logger.debug("Plans recuperes depuis le cache: {} plans, {} prix", plans.size(), prices.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation des plans depuis le cache", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur lors de la recuperation des plans"));
        }
    }

    /**
     * Cree un abonnement gratuit pour un utilisateur
     */
    private void createFreeSubscription(User user) {
        try {
            var freePlan = planRepository.findByCode("FREE");
            if (freePlan.isEmpty()) {
                logger.error("Plan gratuit non trouve dans la base de donnees");
                throw new RuntimeException("Plan gratuit non trouve");
            }

            var billingCustomerOpt = billingCustomerRepository.findByUserId(user.getId());
            BillingCustomer billingCustomer;

            if (billingCustomerOpt.isEmpty()) {
                billingCustomer = new BillingCustomer(user, "internal");
                billingCustomer = billingCustomerRepository.save(billingCustomer);
            } else {
                billingCustomer = billingCustomerOpt.get();
            }

            Subscription freeSubscription = new Subscription();
            freeSubscription.setBillingCustomer(billingCustomer);
            freeSubscription.setPlan(freePlan.get());
            freeSubscription.setStatus("active");
            freeSubscription.setCurrentPeriodStart(LocalDateTime.now());
            freeSubscription.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            freeSubscription.setCancelAtPeriodEnd(false);
            freeSubscription.setCreatedAt(LocalDateTime.now());
            freeSubscription.setUpdatedAt(LocalDateTime.now());

            subscriptionRepository.save(freeSubscription);
            logger.info("Abonnement gratuit cree pour l'utilisateur: {}", user.getId());

            // FREE-recreation path - sync storage quota to FREE allowance.
            quotaSyncer.syncAfterCommit(user.getId(), freePlan.get());

        } catch (Exception e) {
            logger.error("Erreur lors de la creation de l'abonnement gratuit: {}", e.getMessage(), e);
            throw new RuntimeException("Erreur lors de la creation de l'abonnement gratuit", e);
        }
    }

    /**
     * Verifie si un plan est supporte
     */
    private boolean isPlanSupported(String planCode) {
        if ("FREE".equals(planCode)) {
            return true;
        }
        try {
            // Normaliser le code de plan
            String normalizedPlanCode = PlanCode.normalize(planCode);
            if (normalizedPlanCode == null) {
                return false;
            }

            // Verifier si le plan existe dans le cache (qui vient de la DB)
            return planCacheService.getPlan(normalizedPlanCode).isPresent();
        } catch (Exception e) {
            logger.warn("Erreur lors de la verification du plan {}: {}", planCode, e.getMessage());
            return false;
        }
    }

    /**
     * Determine si le nouveau plan est un downgrade
     */
    private boolean isDowngrade(Plan currentPlan, Plan newPlan) {
        if (currentPlan == null || newPlan == null) return false;

        // Hierarchy: lower index = higher tier (downgrade = moving to higher index)
        String[] planHierarchy = {
                "ENTERPRISE_ULTIMATE", "ENTERPRISE_PREMIUM", "ENTERPRISE_STANDARD", "ENTERPRISE_BASIC",
                "TEAM", "PRO", "STARTER", "PAYG", "FREE"
        };

        int currentIndex = -1;
        int newIndex = -1;

        for (int i = 0; i < planHierarchy.length; i++) {
            if (planHierarchy[i].equals(currentPlan.getCode())) currentIndex = i;
            if (planHierarchy[i].equals(newPlan.getCode())) newIndex = i;
        }
        return currentIndex >= 0 && newIndex >= 0 && newIndex > currentIndex;
    }

    // ======================= PLAN CHANGE ENDPOINTS (DOWNGRADE/CYCLE CHANGE) =======================

    /**
     * POST /api/billing/change-plan - Unified plan change (upgrade, downgrade, cycle change)
     * 
     * - Upgrade: applied immediately with proration
     * - Downgrade: scheduled at end of current billing period
     * - Cycle change: scheduled at end of current billing period
     */
    @PostMapping("/change-plan")
    public ResponseEntity<Map<String, Object>> changePlan(
            @RequestBody PlanChangeRequest request,
            HttpServletRequest httpRequest) {
        
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can change the plan.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            logger.info("Change plan request for user {}: target={}, cycle={}, immediate={}",
                    userId, request.getTargetPlanCode(), request.getBillingCycle(), request.getImmediate());

            String targetPlanCode = request.getTargetPlanCode();
            if (targetPlanCode == null || targetPlanCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "targetPlanCode est requis"));
            }

            // Get the current subscription
            var currentSub = subscriptionRepository.findActiveByUserId(userId);
            if (currentSub.isEmpty()) {
                // No active subscription -> redirect to checkout
                return ResponseEntity.ok(Map.of(
                        "action", "checkout_required",
                        "message", "No active subscription, redirecting to checkout"
                ));
            }

            String currentPlanCode = currentSub.get().getPlan().getCode();
            
            // Determine the type of change
            boolean isDowngradeRequest = isDowngrade(
                    planRepository.findByCode(currentPlanCode).orElse(null),
                    planRepository.findByCode(targetPlanCode.toUpperCase()).orElse(null)
            );

            PlanChangeResult result;
            
            if (isDowngradeRequest) {
                // Downgrade -> always scheduled at end of period
                result = stripeScheduleService.scheduleDowngrade(userId, targetPlanCode);
            } else if (request.getBillingCycle() != null) {
                // Billing cycle change
                result = stripeScheduleService.scheduleBillingCycleChange(userId, request.getBillingCycle());
            } else {
                // Upgrade -> redirect to checkout or immediate swap
                try {
                    String billingCycle = request.getBillingCycle() != null ? request.getBillingCycle() : "monthly";
                    int creditTierIndex = request.getCreditTierIndex() != null ? request.getCreditTierIndex() : 0;
                    String checkoutUrl = stripeBillingService.createCheckoutSession(userId, targetPlanCode, billingCycle, creditTierIndex);
                    
                    if (checkoutUrl == null) {
                        // Immediate swap performed
                        result = PlanChangeResult.immediateUpgrade(currentPlanCode, targetPlanCode, "Plan updated immediately");
                    } else {
                        result = PlanChangeResult.checkoutRequired(currentPlanCode, targetPlanCode, checkoutUrl);
                    }
                } catch (Exception e) {
                    result = PlanChangeResult.error(e.getMessage());
                }
            }

            // Build the response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("changeType", result.getChangeType().name());
            response.put("message", result.getMessage());
            response.put("currentPlanCode", result.getCurrentPlanCode());
            response.put("targetPlanCode", result.getTargetPlanCode());
            
            if (result.getEffectiveDate() != null) {
                response.put("effectiveDate", result.getEffectiveDate().toString());
            }
            if (result.getCheckoutUrl() != null) {
                response.put("url", result.getCheckoutUrl());
            }
            if (result.getScheduleId() != null) {
                response.put("scheduleId", result.getScheduleId());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error during plan change", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * POST /api/billing/downgrade - Schedules a downgrade at the end of the billing period
     */
    @PostMapping("/downgrade")
    public ResponseEntity<Map<String, Object>> scheduleDowngrade(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can schedule a downgrade.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            String targetPlanCode = request.get("targetPlanCode");
            if (targetPlanCode == null || targetPlanCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "targetPlanCode is required"));
            }

            logger.info("Scheduling downgrade for user {} to plan {}", userId, targetPlanCode);

            PlanChangeResult result = stripeScheduleService.scheduleDowngrade(userId, targetPlanCode);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("changeType", result.getChangeType().name());
            response.put("message", result.getMessage());
            
            if (result.getEffectiveDate() != null) {
                response.put("effectiveDate", result.getEffectiveDate().toString());
            }
            if (result.getScheduleId() != null) {
                response.put("scheduleId", result.getScheduleId());
            }

            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error scheduling downgrade", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * POST /api/billing/change-credit-tier - Credit tier change
     * - Upgrade: applied immediately with billing_cycle_anchor=NOW (new cycle, full charge, full credits)
     * - Downgrade: scheduled at end of billing period
     */
    @PostMapping("/change-credit-tier")
    public ResponseEntity<Map<String, Object>> changeCreditTier(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) throws StripeException {

        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can change the credit tier.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            Object tierObj = request.get("creditTierIndex");
            if (tierObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "creditTierIndex is required"));
            }

            int creditTierIndex;
            try {
                creditTierIndex = Integer.parseInt(tierObj.toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid creditTierIndex"));
            }

            // Get current credit quantity to determine upgrade vs downgrade
            var currentSub = subscriptionRepository.findActiveByUserId(userId);
            if (currentSub.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No active subscription"));
            }

            int currentCreditQty = currentSub.get().getCreditQuantity() != null
                    ? currentSub.get().getCreditQuantity() : 0;
            String planCode = currentSub.get().getPlan() != null ? currentSub.get().getPlan().getCode() : "FREE";
            int targetCreditQty = CreditTierConstants.getCreditCost(creditTierIndex, planCode);

            PlanChangeResult result;

            if (targetCreditQty > currentCreditQty) {
                // Upgrade: apply immediately with billing_cycle_anchor=NOW
                logger.info("Credit tier upgrade for user {} (qty {} -> {}, tier {})",
                        userId, currentCreditQty, targetCreditQty, creditTierIndex);
                result = stripeBillingService.upgradeCreditTierImmediate(userId, creditTierIndex);
            } else if (targetCreditQty < currentCreditQty) {
                // Downgrade: schedule at end of period
                logger.info("Scheduling credit tier downgrade for user {} (qty {} -> {}, tier {})",
                        userId, currentCreditQty, targetCreditQty, creditTierIndex);
                result = stripeScheduleService.scheduleCreditTierDowngrade(userId, creditTierIndex);
            } else {
                result = PlanChangeResult.error("Already on this credit tier");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("changeType", result.getChangeType().name());
            response.put("message", result.getMessage());

            if (result.getEffectiveDate() != null) {
                response.put("effectiveDate", result.getEffectiveDate().toString());
            }
            if (result.getScheduleId() != null) {
                response.put("scheduleId", result.getScheduleId());
            }
            if (result.getCheckoutUrl() != null) {
                response.put("url", result.getCheckoutUrl());
            }

            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);

        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error changing credit tier", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * POST /api/billing/change-cycle - Changes the billing cycle (monthly <-> yearly)
     */
    @PostMapping("/change-cycle")
    public ResponseEntity<Map<String, Object>> changeBillingCycle(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) throws StripeException {
        
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can change the billing cycle.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            String targetCycle = request.get("billingCycle");
            if (targetCycle == null || (!targetCycle.equals("monthly") && !targetCycle.equals("yearly"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "billingCycle must be 'monthly' or 'yearly'"));
            }

            logger.info("Changing billing cycle for user {} to {}", userId, targetCycle);

            // monthly → yearly: apply immediately via swap (upgrade in commitment)
            if ("yearly".equals(targetCycle)) {
                var localSub = stripeBillingService.getActiveSubscription(userId);
                String planCode = localSub.getPlan().getCode();
                int creditQty = localSub.getCreditQuantity() != null ? localSub.getCreditQuantity() : 0;
                int creditTierIdx = CreditTierConstants.resolveTierIndex(creditQty, planCode);

                stripeBillingService.swapPlanAndUpdateLocal(userId, planCode, creditTierIdx, targetCycle);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("changeType", "IMMEDIATE_CYCLE_CHANGE");
                response.put("message", "Your billing cycle has been switched to yearly immediately.");
                return ResponseEntity.ok(response);
            }

            // yearly → monthly: schedule at end of period
            PlanChangeResult result = stripeScheduleService.scheduleBillingCycleChange(userId, targetCycle);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("changeType", result.getChangeType().name());
            response.put("message", result.getMessage());

            if (result.getEffectiveDate() != null) {
                response.put("effectiveDate", result.getEffectiveDate().toString());
            }
            if (result.getScheduleId() != null) {
                response.put("scheduleId", result.getScheduleId());
            }

            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);

        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error changing billing cycle", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * GET /api/billing/scheduled-change - Gets the scheduled change (if any)
     */
    @GetMapping("/scheduled-change")
    public ResponseEntity<Map<String, Object>> getScheduledChange(HttpServletRequest httpRequest) {
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }

            Optional<ScheduledChangeInfo> scheduled = stripeScheduleService.getScheduledChange(userId);

            if (scheduled.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "hasScheduledChange", false,
                        "scheduledChange", Map.of()
                ));
            }

            ScheduledChangeInfo info = scheduled.get();
            Map<String, Object> changeInfo = new HashMap<>();
            changeInfo.put("scheduleId", info.getScheduleId());
            changeInfo.put("currentPlanCode", info.getCurrentPlanCode());
            changeInfo.put("currentPlanName", info.getCurrentPlanName());
            changeInfo.put("targetPlanCode", info.getTargetPlanCode());
            changeInfo.put("targetPlanName", info.getTargetPlanName());
            changeInfo.put("effectiveDate", info.getEffectiveDate() != null ? info.getEffectiveDate().toString() : null);
            changeInfo.put("changeType", info.getChangeType());
            changeInfo.put("status", info.getStatus());
            changeInfo.put("cancellable", info.isCancellable());
            changeInfo.put("userMessage", info.getUserMessage());
            if (info.getCurrentCreditQty() != null) {
                changeInfo.put("currentCreditQty", info.getCurrentCreditQty());
            }
            if (info.getTargetCreditQty() != null) {
                changeInfo.put("targetCreditQty", info.getTargetCreditQty());
            }

            return ResponseEntity.ok(Map.of(
                    "hasScheduledChange", true,
                    "scheduledChange", changeInfo
            ));

        } catch (Exception e) {
            logger.error("Error getting scheduled change", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/billing/scheduled-change - Cancels a scheduled change
     */
    @DeleteMapping("/scheduled-change")
    public ResponseEntity<Map<String, Object>> cancelScheduledChange(HttpServletRequest httpRequest) {
        try {
            Long userId = extractUserId(httpRequest);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
            }
            // PR5: only OWNER of the active workspace can cancel a scheduled change.
            ResponseEntity<Map<String, Object>> ownerCheck = requireActiveOrgOwner(httpRequest);
            if (ownerCheck != null) return ownerCheck;

            logger.info("Cancelling scheduled change for user {}", userId);

            boolean cancelled = stripeScheduleService.cancelScheduledChange(userId);

            if (cancelled) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Scheduled change cancelled successfully"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "No scheduled change to cancel"
                ));
            }

        } catch (Exception e) {
            logger.error("Error cancelling scheduled change", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * GET /api/billing/invoices - Recent invoices for the authenticated user.
     *
     * <p>Read endpoint, follows the {@code /billing/me} and
     * {@code /scheduled-change} precedent: <b>no {@code requireActiveOrgOwner}
     * gate</b>. Invoices belong to the user (linked via {@code BillingCustomer
     * .user_id}), not to the active workspace, so gating would block a TEAM
     * member from inspecting their own personal billing history.
     *
     * <p>Returns at most 12 invoices, newest-first (Stripe default order).
     * The 12-cap matches one year of monthly billing; users with longer
     * history can click {@code [View all in Stripe →]} in the UI which opens
     * the Stripe Customer Portal where invoice history is unbounded.
     *
     * <p>Each DTO carries both {@code amountPaid} and {@code amountDue}: the
     * frontend selects which to display based on {@code status} (paid →
     * paid; open/uncollectible/draft → due; void → dash). {@code Instant}
     * (UTC-explicit) dates avoid the {@code LocalDateTime} zone ambiguity
     * elsewhere in this service.
     *
     * <p>{@code Cache-Control: no-store} is set because {@code
     * hostedInvoiceUrl} and {@code invoicePdf} are signed URLs that expire.
     */
    @Transactional(readOnly = true)
    @GetMapping("/invoices")
    public ResponseEntity<Map<String, Object>> getInvoices(HttpServletRequest httpRequest) throws StripeException {
        Long userId = extractUserId(httpRequest);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
        }

        Optional<BillingCustomer> bcOpt = billingCustomerRepository.findByUserId(userId);
        if (bcOpt.isEmpty() || bcOpt.get().getProviderCustomerId() == null) {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(Map.of("invoices", List.of()));
        }

        String customerId = bcOpt.get().getProviderCustomerId();

        InvoiceListParams params = InvoiceListParams.builder()
                .setCustomer(customerId)
                .setLimit(12L)
                .build();

        StripeCollection<Invoice> invoices = stripeClient.invoices().list(params);
        List<Invoice> rows = invoices.getData() != null ? invoices.getData() : List.of();

        List<BillingInvoiceDto> dtoList = rows.stream()
                .map(BillingController::toInvoiceDto)
                .toList();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("invoices", dtoList));
    }

    private static BillingInvoiceDto toInvoiceDto(Invoice inv) {
        return BillingInvoiceDto.builder()
                .id(inv.getId())
                .number(inv.getNumber())
                .amountPaid(inv.getAmountPaid())
                .amountDue(inv.getAmountDue())
                .currency(inv.getCurrency())
                .status(inv.getStatus())
                .created(inv.getCreated() != null ? Instant.ofEpochSecond(inv.getCreated()) : null)
                .periodStart(inv.getPeriodStart() != null ? Instant.ofEpochSecond(inv.getPeriodStart()) : null)
                .periodEnd(inv.getPeriodEnd() != null ? Instant.ofEpochSecond(inv.getPeriodEnd()) : null)
                .hostedInvoiceUrl(inv.getHostedInvoiceUrl())
                .invoicePdf(inv.getInvoicePdf())
                .build();
    }
}

