// src/main/java/com/apimarketplace/auth/service/StripeBillingService.java
package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.enums.PlanCode;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.util.NonceUtil;
import com.apimarketplace.auth.service.util.StripeSubscriptionPeriod;
import com.apimarketplace.auth.service.util.BillingMDC;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoiceCreateParams;
import com.stripe.param.InvoiceItemCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.apimarketplace.auth.domain.dto.PlanChangeResult;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    private final StripeClient stripe;
    private final BillingCustomerRepository billingCustomerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final PriceRepository priceRepository;
    private final PriceCacheService priceCacheService;
    private final PlanCacheService planCacheService;
    private final NonceUtil nonceUtil;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;
    private final PlanStorageQuotaSyncer quotaSyncer;

    // V311: pause an owner's over-cap workspaces when a Stripe-side cancel reverts them toward the
    // FREE cap. Field-injected (optional) so the constructor-based unit tests stay slim - null there
    // → no-op, same pattern OrganizationController/OrganizationService use for planResolutionService.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrganizationService organizationService;

    @Value("${billing.stripe.successUrl}")
    private String checkoutSuccessUrl;

    @Value("${billing.stripe.cancelUrl}")
    private String checkoutCancelUrl;

    public StripeBillingService(
            StripeClient stripeClient,
            BillingCustomerRepository billingCustomerRepository,
            SubscriptionRepository subscriptionRepository,
            UserRepository userRepository,
            PlanRepository planRepository,
            PriceRepository priceRepository,
            PriceCacheService priceCacheService,
            PlanCacheService planCacheService,
            NonceUtil nonceUtil,
            PendingCreditUpgradeRepository pendingCreditUpgradeRepository,
            PlanStorageQuotaSyncer quotaSyncer
                               ) {
        this.stripe = stripeClient;
        this.billingCustomerRepository = billingCustomerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.planRepository = planRepository;
        this.priceRepository = priceRepository;
        this.priceCacheService = priceCacheService;
        this.planCacheService = planCacheService;
        this.nonceUtil = nonceUtil;
        this.pendingCreditUpgradeRepository = pendingCreditUpgradeRepository;
        this.quotaSyncer = quotaSyncer;
    }

    // ---------------------- Politique d’upgrade/downgrade ----------------------

    private long planRank(String code) {
        if (code == null) return 0L;

        // Normaliser le code de plan
        String normalizedCode = PlanCode.normalize(code);
        if (normalizedCode == null) {
            log.warn("Code de plan non reconnu: {}", code);
            return 0L;
        }

        // Recuperer l'ordre depuis le cache (utilise l'id comme ordre)
        return planCacheService.getPlanOrder(normalizedCode).orElse(0L);
    }

    private boolean isUpgrade(String currentPlan, String newPlan) {
        return planRank(newPlan) > planRank(currentPlan);
    }

    private boolean isDowngrade(String currentPlan, String newPlan) {
        return planRank(newPlan) < planRank(currentPlan);
    }

    /**
     * Returns the active subscription for a user, or throws if none found.
     */
    public Subscription getActiveSubscription(Long userId) {
        return subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No active subscription for user " + userId));
    }

    /**
     * Recupere le cycle de facturation actuel de l'abonnement local
     */
    private String getCurrentBillingCycle(Subscription subscription) {
        if (subscription == null || subscription.getPrice() == null) {
            return null;
        }

        // Recuperer le priceId actuel et determiner le cycle de facturation
        String currentPriceId = subscription.getPrice().getProviderPriceId();
        if (currentPriceId == null) {
            return null;
        }

        // Utiliser le cache pour determiner le cycle de facturation
        return priceCacheService.getBillingCycleByPriceId(currentPriceId).orElse(null);
    }


    // ---------------------- CREATE CHECKOUT SESSION (standard) -----------------

    public String createCheckoutSession(Long userId, String planCode, String billingCycle) throws Exception {
        return createCheckoutSession(userId, planCode, billingCycle, 0);
    }

    public String createCheckoutSession(Long userId, String planCode, String billingCycle, int creditTierIndex) throws Exception {
        // MDC logging for traceability
        BillingMDC.context(userId, null, "checkout").withPlanCode(planCode);
        BillingMDC.logStart(log, "Creating checkout session for plan {} ({}) creditTier={}", planCode, billingCycle, creditTierIndex);

        // Normaliser le code de plan en utilisant l'enum
        String normalizedPlanCode = PlanCode.normalize(planCode);
        if (normalizedPlanCode == null) {
            BillingMDC.logFailure(log, "Invalid plan code: {}", planCode);
            throw new IllegalArgumentException("Code de plan invalide: " + planCode);
        }

        // Validate credit tier for the plan
        CreditTierConstants.validateTierForPlan(creditTierIndex, normalizedPlanCode);
        int creditQuantity = CreditTierConstants.getCreditCost(creditTierIndex, normalizedPlanCode);
        String creditPriceId = priceCacheService.getCreditPriceId(normalizedPlanCode, billingCycle).orElse(null);

        // Utiliser le cache au lieu de la configuration hardcodee
        String priceId = priceCacheService.getPriceId(normalizedPlanCode, billingCycle)
                                          .orElseThrow(() -> new IllegalArgumentException("Plan non valide: " + planCode));

        User user = userRepository.findById(userId)
                                  .orElseThrow(() -> new IllegalArgumentException("Utilisateur non trouve: " + userId));

        Optional<Subscription> existingOpt = subscriptionRepository.findActiveByUserId(userId);

        com.stripe.model.Subscription stripeSub = null;
        String currentPlanCode = null;
        String providerSubId = null;
        boolean hasAliveStripeSub = false;
        Subscription local = null;

        if (existingOpt.isPresent()) {
            local = existingOpt.get();
            currentPlanCode = (local.getPlan() != null) ? PlanCode.normalize(local.getPlan().getCode()) : null;
            providerSubId = local.getProviderSubscriptionId();

            if (providerSubId != null && !providerSubId.isBlank()) {
                try {
                    stripeSub = stripe.subscriptions().retrieve(providerSubId);
                    hasAliveStripeSub = isAliveStatus(stripeSub.getStatus());

                    // Si la sub Stripe est canceled / non vivante → reconcilier local & ignorer upgrade/downgrade
                    if (!hasAliveStripeSub) {
                        reconcileLocalCanceled(local, "Stripe status=" + stripeSub.getStatus());
                        providerSubId = null; // force la voie Checkout
                    }
                } catch (InvalidRequestException e) {
                    if (isNoSuchSubscriptionError(e)) {
                        // Sub Stripe n'existe plus → reconcilier local & partir en Checkout
                        reconcileLocalCanceled(local, "Stripe says: no such subscription");
                        providerSubId = null;
                    } else {
                        throw e;
                    }
                }
            }
        }

        // Si on a ENCORE une sub Stripe vivante, on traite upgrade/downgrade
        if (providerSubId != null) {
            // Devise guard
            String currentCurrency = getSubscriptionCurrency(stripeSub);
            String newCurrency = fetchPriceCurrency(priceId);
            if (currentCurrency != null && newCurrency != null && !currentCurrency.equalsIgnoreCase(newCurrency)) {
                throw new IllegalArgumentException(
                        "Votre abonnement actuel est en " + currentCurrency.toUpperCase() +
                        " ; choisissez un plan dans la meme devise."
                );
            }

            // Check if this is a same-plan credit tier change or billing cycle change
            boolean isSamePlan = currentPlanCode != null && currentPlanCode.equalsIgnoreCase(planCode);
            int currentCreditQty = local.getCreditQuantity() != null ? local.getCreditQuantity() : 0;
            boolean isCreditTierChange = creditQuantity != currentCreditQty;
            String currentBillingCycle = getCurrentBillingCycle(local);
            boolean isBillingCycleChange = billingCycle != null && currentBillingCycle != null
                    && !billingCycle.equalsIgnoreCase(currentBillingCycle);

            if (isSamePlan && !isCreditTierChange && !isBillingCycleChange) {
                throw new IllegalArgumentException("Vous etes deja sur le plan " + planCode);
            }

            // Note: Les validations de cycle de facturation sont maintenant gerees côte frontend
            // pour eviter les requetes inutiles au serveur

            // Log pour deboguer la hierarchie des plans
            log.info("Plan change analysis: current={} (rank={}), target={} (rank={}), isUpgrade={}, isDowngrade={}, samePlan={}, creditChange={}",
                     currentPlanCode, planRank(currentPlanCode), planCode, planRank(planCode),
                     isUpgrade(currentPlanCode, planCode), isDowngrade(currentPlanCode, planCode),
                     isSamePlan, isCreditTierChange);

            if (isSamePlan && (isCreditTierChange || isBillingCycleChange)) {
                // Same plan, different credit tier or billing cycle → update via swap
                try {
                    swapPlanAndUpdateLocal(userId, planCode, creditTierIndex, billingCycle);
                } catch (InvalidRequestException e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("canceled subscription")) {
                        if (local != null) reconcileLocalCanceled(local, "swap failed: canceled subscription");
                    } else {
                        throw e;
                    }
                    providerSubId = null;
                }
                if (providerSubId != null) {
                    if (isBillingCycleChange) {
                        log.info("Billing cycle change on plan {} applied ({} -> {}), sub={}",
                                planCode, currentBillingCycle, billingCycle, providerSubId);
                    }
                    if (isCreditTierChange) {
                        log.info("Credit tier change on plan {} applied (creditQty {} -> {}), sub={}",
                                planCode, currentCreditQty, creditQuantity, providerSubId);
                    }
                    return null;
                }
            } else if (isUpgrade(currentPlanCode, planCode)) {
                // 👉 Upgrade: swap Stripe + update locale in-place
                //
                // Block combined plan + credit tier changes: the swap path uses
                // Stripe's native proration (ALWAYS_INVOICE) which charges only the
                // delta on the credit pack item, but our webhook grants the FULL
                // new pack. Combining the two would charge ~10€ and grant ~100K
                // credits = silent business loss. Force the user to do plan first,
                // then a separate /change-credit-tier call (Option A flow, charges
                // the full pack). UX surfaces this as two sequential CTAs.
                if (isCreditTierChange) {
                    throw new IllegalArgumentException(
                            "Combined plan and credit-tier change is not supported. " +
                            "Please change your plan first, then upgrade your credit tier separately."
                    );
                }
                try {
                    swapPlanAndUpdateLocal(userId, planCode, creditTierIndex, billingCycle);
                } catch (InvalidRequestException e) {
                    // Si entre-temps Stripe a cancel la sub → fallback Checkout
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("canceled subscription")) {
                        if (local != null) reconcileLocalCanceled(local, "swap failed: canceled subscription");
                    } else {
                        throw e;
                    }
                    // force voie Checkout
                    providerSubId = null;
                }
                if (providerSubId != null) {
                    log.info("Upgrade {} -> {} applique (swap Stripe + update locale), sub={}",
                             currentPlanCode, planCode, providerSubId);
                    return null; // pas de Checkout, le webhook confirmera aussi
                }
                // sinon on tombera plus bas vers Checkout
            } else if (isDowngrade(currentPlanCode, planCode)) {
                throw new IllegalArgumentException(
                        "Retrogradation non autorisee. Veuillez d'abord annuler l'abonnement en cours dans le portail de facturation."
                );
            } else {
                throw new IllegalArgumentException("Changement de plan non supporte.");
            }
        }

        // Ici : pas de sub Stripe vivante -> creer une session de Checkout
        BillingCustomer bc = ensureValidStripeCustomer(user);

        String successUrl = checkoutSuccessUrl + "&session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = checkoutCancelUrl;

        log.info("Creating checkout session with URLs - successUrl: {}, cancelUrl: {}", successUrl, cancelUrl);
        
        // Generer un nonce pour masquer l'ID utilisateur
        String nonce = nonceUtil.generateNonce(userId);
        log.debug("Generated nonce for user {}: {}", userId, nonce);
        
        SessionCreateParams params = buildSessionParams(
                bc.getProviderCustomerId(),
                priceId,
                nonce,
                planCode,
                String.valueOf(bc.getId()),
                successUrl,
                cancelUrl,
                creditPriceId,
                creditQuantity,
                creditTierIndex
                                                       );

        try {
            Session session = stripe.checkout().sessions().create(params);
            log.info("Checkout session creee (user={}, plan={}, session={})", userId, planCode, session.getId());
            return session.getUrl();
        } catch (InvalidRequestException e) {
            if (isNoSuchCustomerError(e)) {
                log.warn("Customer {} introuvable chez Stripe. Recreation + retry…", bc.getProviderCustomerId());
                bc = recreateStripeCustomer(user, bc);
                
                // Regenerer le nonce pour le retry
                String retryNonce = nonceUtil.generateNonce(userId);
                log.debug("Generated retry nonce for user {}: {}", userId, retryNonce);
                
                SessionCreateParams retryParams = buildSessionParams(
                        bc.getProviderCustomerId(),
                        priceId,
                        retryNonce,
                        planCode,
                        String.valueOf(bc.getId()),
                        successUrl,
                        cancelUrl,
                        creditPriceId,
                        creditQuantity,
                        creditTierIndex
                                                                    );
                Session session = stripe.checkout().sessions().create(retryParams);
                log.info("Checkout session creee apres auto-reparation (user={}, session={})", userId, session.getId());
                return session.getUrl();
            }
            throw e;
        }
    }


    /**
     * Swap du plan sur Stripe (update du price sur la meme subscription Stripe),
     * puis MISE a JOUR de la meme ligne locale (pas de cancel/insert).
     * <p>
     * On met a jour : plan, status, quantity, currentPeriodStart/End, cancelAtPeriodEnd, priceId.
     * On laisse la transition de quotas au webhook pour eviter les doubles effets.
     */
    public UpgradeResult swapPlanAndUpdateLocal(Long userId, String newPlanCode) throws Exception {
        return swapPlanAndUpdateLocal(userId, newPlanCode, 0, null);
    }

    public UpgradeResult swapPlanAndUpdateLocal(Long userId, String newPlanCode, int creditTierIndex) throws Exception {
        return swapPlanAndUpdateLocal(userId, newPlanCode, creditTierIndex, null);
    }

    public UpgradeResult swapPlanAndUpdateLocal(Long userId, String newPlanCode, int creditTierIndex, String billingCycleOverride) throws Exception {
        // MDC logging for traceability
        BillingMDC.context(userId, null, "upgrade").withPlanCode(newPlanCode);
        BillingMDC.logStart(log, "Swapping plan to {} creditTier={}", newPlanCode, creditTierIndex);

        if (newPlanCode == null || newPlanCode.isBlank()) {
            BillingMDC.logFailure(log, "planCode is required");
            throw new IllegalArgumentException("planCode est requis");
        }
        // Normaliser le code de plan (convertir en majuscules)
        String normalizedPlanCode = newPlanCode.toUpperCase();

        // Recuperer l'abonnement actuel pour determiner le cycle de facturation
        Subscription local = subscriptionRepository.findActiveByUserId(userId)
                                                   .orElseThrow(() -> new IllegalStateException("Aucun abonnement actif a modifier"));

        // Use override from caller if provided, otherwise detect from current subscription
        String currentBillingCycle = billingCycleOverride;
        if (currentBillingCycle == null) {
            currentBillingCycle = getCurrentBillingCycle(local);
        }
        if (currentBillingCycle == null) {
            currentBillingCycle = "monthly"; // Fallback par defaut
        }

        log.info("Upgrade plan: {} -> {} (keeping billing cycle: {})",
                 local.getPlan().getCode(), normalizedPlanCode, currentBillingCycle);

        String newPriceId = priceCacheService.getPriceId(normalizedPlanCode, currentBillingCycle)
                                                   .orElseThrow(() -> new IllegalArgumentException("Plan non valide: " + newPlanCode));

        // 1) Contexte local
        User user = userRepository.findById(userId)
                                  .orElseThrow(() -> new IllegalArgumentException("Utilisateur non trouve: " + userId));

        if (local.getProviderSubscriptionId() == null || local.getProviderSubscriptionId().isBlank()) {
            throw new IllegalStateException("Aucun abonnement Stripe actif lie a cet utilisateur");
        }

        String providerSubId = local.getProviderSubscriptionId();
        String oldPlanCode = (local.getPlan() != null) ? local.getPlan().getCode() : null;

        // 2) Recup Stripe sub + garde-fou devise
        var stripeSub = stripe.subscriptions().retrieve(providerSubId);
        if (stripeSub == null || !isAliveStatus(stripeSub.getStatus())) {
            throw new IllegalStateException("Subscription Stripe non vivante (status=" +
                                            (stripeSub != null ? stripeSub.getStatus() : "unknown") + ")");
        }

        // 2b) Detect actual billing interval from Stripe subscription items (source of truth)
        String stripeInterval = null;
        for (var item : stripeSub.getItems().getData()) {
            if (!priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                var recurring = item.getPrice().getRecurring();
                if (recurring != null) {
                    stripeInterval = "year".equals(recurring.getInterval()) ? "yearly" : "monthly";
                }
                break;
            }
        }
        boolean isBillingCycleChange = billingCycleOverride != null && stripeInterval != null
                && !billingCycleOverride.equals(stripeInterval);
        if (!isBillingCycleChange && stripeInterval != null && !stripeInterval.equals(currentBillingCycle)) {
            // Only override when there is NO intentional billing cycle change (safety check)
            log.warn("Billing cycle mismatch: local={} but Stripe actual interval={}. Using Stripe interval.",
                     currentBillingCycle, stripeInterval);
            currentBillingCycle = stripeInterval;
        }
        if (isBillingCycleChange) {
            log.info("Billing cycle change requested: {} -> {}", stripeInterval, billingCycleOverride);
        }
        // Re-resolve price ID with the final billing cycle (in case it was corrected)
        final String resolvedBillingCycle = currentBillingCycle;
        newPriceId = priceCacheService.getPriceId(normalizedPlanCode, resolvedBillingCycle)
                .orElseThrow(() -> new IllegalArgumentException("Plan non valide pour cycle " + resolvedBillingCycle + ": " + newPlanCode));

        String currentCurrency = getSubscriptionCurrency(stripeSub);
        String newPriceCurrency = fetchPriceCurrency(newPriceId);
        if (currentCurrency != null && newPriceCurrency != null &&
            !currentCurrency.equalsIgnoreCase(newPriceCurrency)) {
            throw new IllegalArgumentException(
                    "Le changement de devise n'est pas autorise automatiquement (" +
                    currentCurrency.toUpperCase() + " → " + newPriceCurrency.toUpperCase() +
                    "). Choisissez un plan dans la meme devise."
            );
        }

        // 3) Credit pack resolution
        int creditQuantity = CreditTierConstants.getCreditCost(creditTierIndex, normalizedPlanCode);
        String creditPriceId = priceCacheService.getCreditPriceId(normalizedPlanCode, currentBillingCycle).orElse(null);

        // 4) Identify existing base and credit items
        String baseItemId = null;
        String creditItemId = null;
        for (var item : stripeSub.getItems().getData()) {
            if (priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                creditItemId = item.getId();
            } else {
                baseItemId = item.getId();
            }
        }

        if (baseItemId == null) {
            throw new IllegalStateException("No base plan item found in Stripe subscription " + providerSubId);
        }

        var updBuilder = SubscriptionUpdateParams.builder()
                .setBillingCycleAnchor(isBillingCycleChange
                        ? SubscriptionUpdateParams.BillingCycleAnchor.NOW
                        : SubscriptionUpdateParams.BillingCycleAnchor.UNCHANGED)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                .addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(baseItemId)
                        .setPrice(newPriceId)
                        .build());

        // Handle credit item: add/update/remove
        if (creditQuantity > 0 && creditPriceId != null) {
            var creditItemBuilder = SubscriptionUpdateParams.Item.builder()
                    .setPrice(creditPriceId)
                    .setQuantity((long) creditQuantity);
            if (creditItemId != null) {
                creditItemBuilder.setId(creditItemId); // Update existing
            }
            updBuilder.addItem(creditItemBuilder.build());
        } else if (creditItemId != null) {
            // Remove credit item (quantity went to 0)
            updBuilder.addItem(SubscriptionUpdateParams.Item.builder()
                    .setId(creditItemId)
                    .setDeleted(true)
                    .build());
        }

        var upd = updBuilder.build();

        var updatedStripeSub = stripe.subscriptions().update(providerSubId, upd);
        log.info("Swap Stripe OK: sub={} price->{}", providerSubId, newPriceId);

        // 5) Donnees Stripe utiles pour mise a jour locale
        int qty = 1;

        // Utiliser le nouvel utilitaire pour récupérer les périodes (SDK 31+)
        StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(updatedStripeSub);
        LocalDateTime cps = period.start();
        LocalDateTime cpe = period.end();
        boolean cancelAtPeriodEnd = Boolean.TRUE.equals(updatedStripeSub.getCancelAtPeriodEnd());

        // 6) Resoudre plan cible
        Plan newPlan = planRepository.findByCode(newPlanCode)
                                     .orElseThrow(() -> new IllegalArgumentException("Plan introuvable: " + newPlanCode));

        // 7) Mise a jour "in place" de la meme row locale
        local.setPlan(newPlan);
        local.setStatus(updatedStripeSub.getStatus() != null ? updatedStripeSub.getStatus() : "active");
        local.setQuantity(qty);
        if (cps != null) local.setCurrentPeriodStart(cps);
        if (cpe != null) local.setCurrentPeriodEnd(cpe);
        local.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        if (isBillingCycleChange) {
            local.setCadence(billingCycleOverride);
            log.info("Cadence updated: {} -> {}", stripeInterval, billingCycleOverride);
        }
        // si tu as un champ priceId en DB (vu dans tes logs d'insert), tiens-le a jour :
        try {
            var m = Subscription.class.getMethod("setPriceId", String.class);
            m.invoke(local, newPriceId);
        } catch (NoSuchMethodException ignored) {
            // si pas de champ priceId côte entite, ignore simplement
        }
        local.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(local);

        log.info("Subscription locale mise a jour in-place: id={}, plan {} -> {}",
                 local.getId(), oldPlanCode, newPlanCode);

        // Plan-swap path: keep tenant_storage_quota in sync with the new plan
        // (only on actual transition - same gate as SubscriptionService:312,
        // with the same defensive null-on-either-side check so the guard
        // doesn't NPE if a future Plan invariant slips).
        if (oldPlanCode == null || !oldPlanCode.equals(newPlanCode)) {
            quotaSyncer.syncAfterCommit(local.getBillingCustomer().getUser().getId(), newPlan);
        }

        return new UpgradeResult(providerSubId, oldPlanCode, newPlanCode, local.getId());
    }

    // ---------------------- Immediate Credit Tier Upgrade -----------------------

    /**
     * Upgrades the credit tier immediately using the Option A flow:
     *
     * <ol>
     *   <li>Create a one-shot {@code InvoiceItem} priced at the FULL new tier
     *       (not attached to a subscription).</li>
     *   <li>Wrap it in a dedicated {@code Invoice}, finalize and pay it
     *       synchronously - the user is charged the full new tier price right now.</li>
     *   <li>Update the subscription's credit sub-item ({@code price} + {@code quantity})
     *       with {@code proration_behavior=NONE} and {@code billing_cycle_anchor=UNCHANGED}
     *       so that the existing base-plan cycle is NOT re-anchored. The new tier price
     *       only takes effect at the next natural renewal.</li>
     *   <li>Grant the full new pack credits to the ledger - triggered asynchronously
     *       by the {@code invoice.paid} webhook with idempotent {@code source_id}.</li>
     * </ol>
     *
     * <p>Replaces the previous {@code billing_cycle_anchor=NOW} pattern, which had the
     * side-effect of re-charging the base plan in addition to the credit pack
     * (5/5 Opus audit consensus, R1 + R2, score 9.36/10).
     *
     * <p>Idempotency: a {@link PendingCreditUpgrade} row is persisted after invoice
     * creation; the row's {@code stripeInvoiceId} is UNIQUE. The webhook handler
     * looks up the row to route the grant. The ledger's V6 partial UNIQUE on
     * {@code source_id} absorbs duplicate webhook deliveries.
     *
     * <p>Important constraint: the user must understand that the immediately-granted
     * credits will be RESET at the next natural renewal - this is by design of the
     * pre-paid pack model. The frontend modal MUST surface this clearly.
     *
     * @param userId               User ID
     * @param targetCreditTierIndex Target credit tier index (must be higher than current)
     * @return PlanChangeResult
     */
    public PlanChangeResult upgradeCreditTierImmediate(Long userId, int targetCreditTierIndex) throws Exception {
        BillingMDC.context(userId, null, "credit_upgrade");
        BillingMDC.logStart(log, "Immediate credit tier upgrade to tier {}", targetCreditTierIndex);

        Subscription local = subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No active subscription for user " + userId));

        String currentPlanCode = local.getPlan().getCode();
        String providerSubId = local.getProviderSubscriptionId();

        if (providerSubId == null || providerSubId.isBlank()) {
            return PlanChangeResult.error("No linked Stripe subscription");
        }

        // Validate tier for plan
        CreditTierConstants.validateTierForPlan(targetCreditTierIndex, currentPlanCode);
        int targetCreditQty = CreditTierConstants.getCreditCost(targetCreditTierIndex, currentPlanCode);

        // Retrieve Stripe subscription
        var stripeSub = stripe.subscriptions().retrieve(providerSubId);
        if (stripeSub == null || !isAliveStatus(stripeSub.getStatus())) {
            return PlanChangeResult.error("Stripe subscription is not active");
        }

        // Resolve payment method: subscription's default > customer's invoice_settings.
        // Validate each candidate is still attached to the customer - Stripe rejects
        // invoice creation with a detached PM ("payment method not attached").
        BillingCustomer billingCustomer = local.getBillingCustomer();
        if (billingCustomer == null || billingCustomer.getProviderCustomerId() == null) {
            return PlanChangeResult.error("No linked Stripe customer for this subscription");
        }
        String stripeCustomerId = billingCustomer.getProviderCustomerId();
        String resolvedPaymentMethod = verifyPaymentMethodAttached(
                stripeSub.getDefaultPaymentMethod(), stripeCustomerId);
        if (resolvedPaymentMethod == null) {
            try {
                var customer = stripe.customers().retrieve(stripeCustomerId);
                if (customer.getInvoiceSettings() != null) {
                    resolvedPaymentMethod = verifyPaymentMethodAttached(
                            customer.getInvoiceSettings().getDefaultPaymentMethod(), stripeCustomerId);
                }
            } catch (Exception e) {
                log.warn("Could not resolve customer payment method: {}", e.getMessage());
            }
        }
        if (resolvedPaymentMethod == null) {
            resolvedPaymentMethod = findAnyAttachedPaymentMethod(stripeCustomerId);
        }
        // resolvedPaymentMethod may be null - handled at step 4 below:
        // if null, we finalize the invoice and redirect to Stripe's hosted payment page
        // instead of trying to charge server-side.

        // Release any pending schedule (e.g. a previous scheduled downgrade) so that
        // the sub-item update below does not collide with an active SubscriptionSchedule.
        String existingScheduleId = stripeSub.getSchedule();
        if (existingScheduleId != null && !existingScheduleId.isBlank()) {
            try {
                var releaseParams = SubscriptionScheduleReleaseParams.builder()
                        .setPreserveCancelDate(false)
                        .build();
                stripe.subscriptionSchedules().release(existingScheduleId, releaseParams);
                log.info("Released existing schedule {} before credit tier upgrade", existingScheduleId);
                stripeSub = stripe.subscriptions().retrieve(providerSubId);
            } catch (Exception e) {
                log.warn("Failed to release schedule {}: {}", existingScheduleId, e.getMessage());
            }
        }

        // Identify existing credit item (may be null when going from tier 0 to tier N).
        String creditItemId = null;
        int currentCreditQty = 0;
        for (var item : stripeSub.getItems().getData()) {
            if (priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                creditItemId = item.getId();
                currentCreditQty = item.getQuantity() != null ? item.getQuantity().intValue() : 0;
            }
        }

        // Verify this is an upgrade
        if (targetCreditQty <= currentCreditQty) {
            return PlanChangeResult.error("Target credit tier is not an upgrade (target="
                    + targetCreditQty + ", current=" + currentCreditQty + ")");
        }

        // Resolve target credit price (matches current billing cycle).
        String currentBillingCycle = getCurrentBillingCycle(local);
        String targetCreditPriceId = priceCacheService.getCreditPriceId(currentPlanCode, currentBillingCycle).orElse(null);
        if (targetCreditPriceId == null) {
            return PlanChangeResult.error("Cannot resolve credit price for plan " + currentPlanCode
                    + " (" + currentBillingCycle + ")");
        }

        // stripeCustomerId already validated above (PM resolution block).

        // Idempotency root: stable across retries within the same period. Re-issuing
        // the SAME call with the SAME params returns the SAME invoice (Stripe semantics).
        long periodStartEpoch = (local.getCurrentPeriodStart() != null)
                ? local.getCurrentPeriodStart().toEpochSecond(java.time.ZoneOffset.UTC)
                : 0L;
        // v2 suffix: param shape changed from setPricing (broken on recurring prices)
        // to setAmount+setCurrency. Old idempotency keys would conflict otherwise.
        //
        // Retry suffix: Stripe caches idempotent responses for 24h. Without this,
        // a user whose card was declined cannot retry within the same period -
        // Stripe replays the cached "card_declined" response even after the user
        // has fixed their payment method. We append `:r<N>` where N is the count
        // of FAILED attempts for the same (sub, tier, qty), guaranteeing a fresh
        // Stripe attempt each time the user retries after fixing their card.
        long failedRetries = pendingCreditUpgradeRepository
                .countByProviderSubscriptionIdAndTargetTierIndexAndTargetCreditQuantityAndStatus(
                        providerSubId, targetCreditTierIndex, targetCreditQty,
                        PendingCreditUpgrade.STATUS_FAILED);
        String ikRoot = "upgrade-v3:" + providerSubId + ":t" + targetCreditTierIndex
                + ":qty" + targetCreditQty + ":p" + periodStartEpoch
                + (failedRetries > 0 ? ":r" + failedRetries : "")
                + (resolvedPaymentMethod == null ? ":noPm" : "");

        // -------- Step 1: create the one-shot InvoiceItem at FULL new tier price --------
        // CRITICAL constraints:
        //   - Do NOT pass setSubscription(...) - Stripe would attach the item to the
        //     next renewal invoice instead of letting us bill it now.
        //   - Stripe SDK 31+ rejects setPricing(price=<recurring>) with "this field only
        //     accepts prices with type=one_time". The credit pack price IS recurring
        //     (it's a subscription item). We therefore resolve the price's unit_amount
        //     and currency, then bill via setAmount + setCurrency directly.
        Price targetPrice = stripe.prices().retrieve(targetCreditPriceId);
        if (targetPrice.getUnitAmount() == null) {
            return PlanChangeResult.error("Credit pack price " + targetCreditPriceId
                    + " has no unit_amount - cannot bill ad-hoc");
        }
        long totalAmountCents = targetPrice.getUnitAmount() * (long) targetCreditQty;

        InvoiceItemCreateParams iiParams = InvoiceItemCreateParams.builder()
                .setCustomer(stripeCustomerId)
                .setAmount(totalAmountCents)
                .setCurrency(targetPrice.getCurrency())
                .setDescription("Credit pack upgrade - tier " + targetCreditTierIndex)
                .putMetadata("kind", "credit_upgrade")
                .putMetadata("userId", String.valueOf(userId))
                .putMetadata("subscriptionId", String.valueOf(local.getId()))
                .putMetadata("providerSubscriptionId", providerSubId)
                .putMetadata("targetTierIndex", String.valueOf(targetCreditTierIndex))
                .putMetadata("targetCreditQuantity", String.valueOf(targetCreditQty))
                .putMetadata("targetCreditPriceId", targetCreditPriceId)
                .putMetadata("ikRoot", ikRoot)
                .build();
        InvoiceItem invoiceItem = stripe.invoiceItems().create(iiParams, idempotent(ikRoot + ":item"));

        // -------- Step 2 + 3: create the dedicated Invoice and persist the pending row --
        // Wrapped to guarantee orphan-InvoiceItem cleanup if either step throws -
        // otherwise the orphan would auto-attach to the customer's NEXT invoice
        // (renewal), silently re-billing the upgrade price.
        Invoice invoice;
        PendingCreditUpgrade pending;
        try {
            // DELIBERATE: no automatic_tax on this standalone invoice (unlike the Checkout
            // sessions). Customers without a saved tax location would fail finalize with
            // customer_tax_location_invalid, and this path is a crash-recoverable payment
            // state machine (pending row + reconcile) - adding a tax-failure branch needs
            // its own design + e2e. Follow-up tracked: enable automatic_tax here once a
            // no-address fallback is specified.
            var invBuilder = InvoiceCreateParams.builder()
                    .setCustomer(stripeCustomerId)
                    .setAutoAdvance(false)
                    .setCollectionMethod(InvoiceCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY);
            if (resolvedPaymentMethod != null) {
                invBuilder.setDefaultPaymentMethod(resolvedPaymentMethod);
            }
            InvoiceCreateParams invParams = invBuilder
                    .setPendingInvoiceItemsBehavior(InvoiceCreateParams.PendingInvoiceItemsBehavior.INCLUDE)
                    .putMetadata("kind", "credit_upgrade")
                    .putMetadata("userId", String.valueOf(userId))
                    .putMetadata("subscriptionId", String.valueOf(local.getId()))
                    .putMetadata("providerSubscriptionId", providerSubId)
                    .putMetadata("targetTierIndex", String.valueOf(targetCreditTierIndex))
                    .putMetadata("targetCreditQuantity", String.valueOf(targetCreditQty))
                    .putMetadata("targetCreditPriceId", targetCreditPriceId)
                    .putMetadata("ikRoot", ikRoot)
                    .build();
            invoice = stripe.invoices().create(invParams, idempotent(ikRoot + ":inv"));

            // Persist the pending-upgrade row BEFORE attempting payment.
            // Webhook handlers and the reconcile job rely on this row to route the
            // grant and recover from a crash between pay() and subscriptions.update().
            pending = pendingCreditUpgradeRepository
                    .findByStripeInvoiceId(invoice.getId())
                    .orElseGet(PendingCreditUpgrade::new);
            if (pending.getId() == null) {
                pending.setUserId(userId);
                pending.setSubscriptionId(local.getId());
                pending.setProviderSubscriptionId(providerSubId);
                pending.setStripeInvoiceId(invoice.getId());
                pending.setStripeInvoiceItemId(invoiceItem.getId());
                pending.setTargetTierIndex(targetCreditTierIndex);
                pending.setTargetCreditQuantity(targetCreditQty);
                pending.setTargetCreditPriceId(targetCreditPriceId);
                pending.setStatus(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING);
                pendingCreditUpgradeRepository.save(pending);
            }
        } catch (StripeException | RuntimeException ex) {
            log.error("Failed to create invoice / persist pending upgrade (user={}, invoiceItem={}): {}",
                    userId, invoiceItem.getId(), ex.getMessage(), ex);
            // Best-effort cleanup before propagating:
            //   1) Void the draft invoice if it was created (keeps it un-finalizable
            //      so it cannot ride a future renewal even if Stripe re-attaches it).
            //   2) Delete the orphan InvoiceItem (otherwise auto-attached to the
            //      customer's NEXT subscription invoice = silent re-billing).
            // Both wrapped in try/catch - errors logged, original exception rethrown.
            // The `invoice` local is scoped to the try block; we resolve any draft
            // invoice the InvoiceItem may have been attached to via Stripe lookup.
            try {
                var ii = stripe.invoiceItems().retrieve(invoiceItem.getId());
                if (ii != null && ii.getInvoice() != null) {
                    stripe.invoices().voidInvoice(ii.getInvoice());
                    log.info("Voided draft invoice {} after step-2/3 failure", ii.getInvoice());
                }
            } catch (Exception voidEx) {
                log.warn("Could not void draft invoice for item {}: {}", invoiceItem.getId(), voidEx.getMessage());
            }
            try {
                stripe.invoiceItems().delete(invoiceItem.getId());
                log.info("Deleted orphan invoice item {} after step-2/3 failure", invoiceItem.getId());
            } catch (Exception deleteEx) {
                log.warn("Could not delete orphan invoice item {}: {}", invoiceItem.getId(), deleteEx.getMessage());
            }
            if (ex instanceof StripeException) throw (StripeException) ex;
            throw (RuntimeException) ex;
        }

        // -------- Step 4: finalize + pay (or redirect to hosted invoice) ----------------
        try {
            invoice = stripe.invoices().finalizeInvoice(invoice.getId(), idempotent(ikRoot + ":fin"));
        } catch (StripeException se) {
            log.error("Failed to finalize credit upgrade invoice (user={}, invoice={}): {}",
                    userId, invoice.getId(), se.getMessage(), se);
            voidInvoiceAndItemQuietly(invoice.getId(), invoiceItem.getId());
            pending.setStatus(PendingCreditUpgrade.STATUS_FAILED);
            pending.setErrorMessage("finalize_failed: " + se.getMessage());
            pendingCreditUpgradeRepository.save(pending);
            return PlanChangeResult.error("Payment temporarily unavailable. Please retry shortly.");
        }

        // No valid PM → redirect to Stripe's hosted invoice page where the customer
        // can enter a card and pay directly. The webhook invoice.paid handles the
        // credit grant + subscription update when payment completes.
        if (resolvedPaymentMethod == null) {
            String hostedUrl = invoice.getHostedInvoiceUrl();
            if (hostedUrl == null || hostedUrl.isBlank()) {
                log.error("No hosted invoice URL after finalize (user={}, invoice={})", userId, invoice.getId());
                voidInvoiceAndItemQuietly(invoice.getId(), invoiceItem.getId());
                pending.setStatus(PendingCreditUpgrade.STATUS_FAILED);
                pending.setErrorMessage("hosted_invoice_url_missing");
                pendingCreditUpgradeRepository.save(pending);
                return PlanChangeResult.error("Payment temporarily unavailable. Please retry shortly.");
            }
            log.info("No PM on file for user {} - redirecting to hosted invoice {} (url={})",
                    userId, invoice.getId(), hostedUrl);
            pending.setStatus(PendingCreditUpgrade.STATUS_PENDING_3DS);
            pendingCreditUpgradeRepository.save(pending);
            return PlanChangeResult.checkoutRequired(currentPlanCode, currentPlanCode, hostedUrl);
        }

        try {
            invoice = stripe.invoices().pay(invoice.getId(), idempotent(ikRoot + ":pay"));
        } catch (CardException ce) {
            log.warn("Card declined for credit upgrade (user={}, invoice={}): {}",
                    userId, invoice.getId(), ce.getMessage());
            voidInvoiceAndItemQuietly(invoice.getId(), invoiceItem.getId());
            pending.setStatus(PendingCreditUpgrade.STATUS_FAILED);
            pending.setErrorMessage("card_declined: " + ce.getMessage());
            pendingCreditUpgradeRepository.save(pending);
            return PlanChangeResult.error("Payment declined: " + safeStripeMessage(ce));
        } catch (StripeException se) {
            log.error("Stripe error during credit upgrade pay (user={}, invoice={}): {}",
                    userId, invoice.getId(), se.getMessage(), se);
            return PlanChangeResult.error("Payment temporarily unavailable. Please retry shortly.");
        }

        if (!"paid".equalsIgnoreCase(invoice.getStatus())) {
            log.info("Credit upgrade invoice not yet paid (status={}, user={}, invoice={}) - likely 3DS",
                    invoice.getStatus(), userId, invoice.getId());
            pending.setStatus(PendingCreditUpgrade.STATUS_PENDING_3DS);
            pendingCreditUpgradeRepository.save(pending);
            String hostedUrl = invoice.getHostedInvoiceUrl();
            if (hostedUrl != null && !hostedUrl.isBlank()) {
                return PlanChangeResult.checkoutRequired(currentPlanCode, currentPlanCode, hostedUrl);
            }
            return PlanChangeResult.error("Additional authentication required. " +
                    "Please complete the payment from your billing portal.");
        }

        // -------- Step 5: align the sub-item for future renewals ----------------------
        // Cycle plan préservé: pas de billing_cycle_anchor, pas de prorata.
        // En cas d'échec, le reconcile job rejoue cette étape (idempotent via Idempotency-Key).
        try {
            SubscriptionUpdateParams.Builder subUpd = SubscriptionUpdateParams.builder()
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE);
            if (resolvedPaymentMethod != null) {
                subUpd.setDefaultPaymentMethod(resolvedPaymentMethod);
            }
            if (creditItemId != null) {
                subUpd.addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(creditItemId)
                        .setPrice(targetCreditPriceId)
                        .setQuantity((long) targetCreditQty)
                        .build());
            } else {
                subUpd.addItem(SubscriptionUpdateParams.Item.builder()
                        .setPrice(targetCreditPriceId)
                        .setQuantity((long) targetCreditQty)
                        .build());
            }
            stripe.subscriptions().update(providerSubId, subUpd.build(), idempotent(ikRoot + ":sub"));
            log.info("Sub-item credit pack updated for next renewals: sub={}, qty {} -> {}",
                    providerSubId, currentCreditQty, targetCreditQty);
        } catch (StripeException se) {
            // Payment succeeded but sub-item could not be aligned. Keep status as
            // PAID_SUB_PENDING so the reconciler retries the sub-item alignment.
            log.error("Sub-item update failed after pay (user={}, sub={}): {} - leaving for reconcile",
                    userId, providerSubId, se.getMessage(), se);
            local.setCreditQuantity(targetCreditQty);
            local.setUpdatedAt(java.time.LocalDateTime.now());
            subscriptionRepository.save(local);
            return PlanChangeResult.immediateCreditUpgrade(
                    currentPlanCode,
                    "Credit pack upgraded to tier " + targetCreditTierIndex
                            + ". Full pack credits will be granted shortly.");
        }

        // -------- Step 6: update local Subscription state + mark pending COMPLETED ----
        local.setCreditQuantity(targetCreditQty);
        local.setUpdatedAt(java.time.LocalDateTime.now());
        subscriptionRepository.save(local);

        pending.setStatus(PendingCreditUpgrade.STATUS_COMPLETED);
        pending.setCompletedAt(java.time.LocalDateTime.now());
        pendingCreditUpgradeRepository.save(pending);

        return PlanChangeResult.immediateCreditUpgrade(
                currentPlanCode,
                "Credit pack upgraded to tier " + targetCreditTierIndex
                        + ". Full pack credits will be granted shortly."
        );
    }

    // ---------------------- Helpers Stripe / DB --------------------------------

    /**
     * Returns a {@link RequestOptions} carrying the given Stripe idempotency key,
     * truncated to Stripe's 255-char limit. Used to make every API call in the
     * credit-upgrade flow safely retry-able.
     */
    private static RequestOptions idempotent(String key) {
        String safe = key.length() > 255 ? key.substring(0, 255) : key;
        return RequestOptions.builder().setIdempotencyKey(safe).build();
    }

    /**
     * Best-effort rollback when the payment is declined: void the invoice (which
     * detaches it from the customer's open balance) and delete the orphaned
     * invoice item. Failures are logged but never thrown - the pending row is
     * already marked FAILED upstream, so the user is consistently denied the grant.
     */
    private void voidInvoiceAndItemQuietly(String invoiceId, String invoiceItemId) {
        try {
            stripe.invoices().voidInvoice(invoiceId);
            log.info("Voided invoice {} after payment failure", invoiceId);
        } catch (Exception e) {
            log.warn("Could not void invoice {} after payment failure: {}", invoiceId, e.getMessage());
        }
        try {
            stripe.invoiceItems().delete(invoiceItemId);
            log.info("Deleted orphan invoice item {} after payment failure", invoiceItemId);
        } catch (Exception e) {
            log.warn("Could not delete invoice item {} after payment failure: {}", invoiceItemId, e.getMessage());
        }
    }

    /**
     * User-facing Stripe error message - strips internal codes/IDs.
     */
    private static String safeStripeMessage(StripeException e) {
        String msg = e.getUserMessage();
        if (msg == null || msg.isBlank()) msg = e.getMessage();
        if (msg == null) msg = "Card was declined.";
        return msg;
    }

    /**
     * Lists the customer's attached payment methods and returns the first one.
     * Used as a last-resort fallback when neither the subscription's nor the
     * customer's default PM is valid.
     */
    public String findAnyAttachedPaymentMethod(String customerId) {
        try {
            var params = com.stripe.param.PaymentMethodListParams.builder()
                    .setCustomer(customerId)
                    .setType(com.stripe.param.PaymentMethodListParams.Type.CARD)
                    .setLimit(1L)
                    .build();
            var list = stripe.paymentMethods().list(params);
            if (list != null && list.getData() != null && !list.getData().isEmpty()) {
                String pmId = list.getData().get(0).getId();
                log.info("Fallback: found attached PM {} for customer {}", pmId, customerId);
                return pmId;
            }
            // Also try Link payment methods
            var linkParams = com.stripe.param.PaymentMethodListParams.builder()
                    .setCustomer(customerId)
                    .setType(com.stripe.param.PaymentMethodListParams.Type.LINK)
                    .setLimit(1L)
                    .build();
            var linkList = stripe.paymentMethods().list(linkParams);
            if (linkList != null && linkList.getData() != null && !linkList.getData().isEmpty()) {
                String pmId = linkList.getData().get(0).getId();
                log.info("Fallback: found attached Link PM {} for customer {}", pmId, customerId);
                return pmId;
            }
        } catch (Exception e) {
            log.warn("Could not list payment methods for customer {}: {}", customerId, e.getMessage());
        }
        log.warn("No attached payment methods found for customer {} (checked CARD + LINK)", customerId);
        return null;
    }

    private String fetchPriceCurrency(String priceId) throws StripeException {
        Price p = stripe.prices().retrieve(priceId);
        return p != null ? p.getCurrency() : null;
    }

    private String getSubscriptionCurrency(com.stripe.model.Subscription sub) {
        try {
            return sub.getItems().getData().get(0).getPrice().getCurrency();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Returns {@code pmId} if it is non-blank AND still attached to {@code customerId}.
     * Returns {@code null} otherwise (detached, deleted, or Stripe API error).
     */
    private String verifyPaymentMethodAttached(String pmId, String customerId) {
        if (pmId == null || pmId.isBlank()) return null;
        try {
            var pm = stripe.paymentMethods().retrieve(pmId);
            if (pm != null && customerId.equals(pm.getCustomer())) {
                return pmId;
            }
            log.warn("Payment method {} not attached to customer {} (attached to: {})",
                    pmId, customerId, pm != null ? pm.getCustomer() : "null");
        } catch (Exception e) {
            log.warn("Cannot verify payment method {}: {}", pmId, e.getMessage());
        }
        return null;
    }

    public String createBillingPortalSession(Long userId, String returnUrl) throws Exception {
        User user = userRepository.findById(userId)
                                  .orElseThrow(() -> new IllegalArgumentException("User not found"));
        BillingCustomer bc = ensureValidStripeCustomer(user);

        var p = com.stripe.param.billingportal.SessionCreateParams.builder()
                                                                  .setCustomer(bc.getProviderCustomerId())
                                                                  .setReturnUrl(returnUrl != null ? returnUrl : checkoutSuccessUrl)
                                                                  .build();

        var session = stripe.billingPortal().sessions().create(p);
        log.info("Billing portal session creee (user={}, session={})", userId, session.getId());
        return session.getUrl();
    }

    /**
     * Verifie que le customer existe encore côte Stripe. Recree si supprime.
     * S'assure aussi que le nonce est present dans les metadonnees du customer.
     */
    private BillingCustomer ensureValidStripeCustomer(User user) throws StripeException {
        BillingCustomer bc = billingCustomerRepository.findByUserId(user.getId())
                                                      .orElseGet(() -> new BillingCustomer(user, "stripe"));

        String customerId = bc.getProviderCustomerId();
        if (customerId != null && !customerId.isBlank()) {
            try {
                Customer customer = stripe.customers().retrieve(customerId);

                String nonce = nonceUtil.generateNonce(user.getId());

                // Only stamp the name when Stripe has none: with tax_id_collection,
                // Checkout saves the BUSINESS name the customer entered with their VAT
                // number (customer_update.name=auto) - re-stamping the personal name on
                // every checkout/portal visit would clobber it and produce invoices with
                // a personal name next to a business VAT ID.
                var updateBuilder = com.stripe.param.CustomerUpdateParams.builder()
                        .putMetadata("nonce", nonce);
                String existingName = customer.getName();
                if (existingName == null || existingName.isBlank()) {
                    updateBuilder.setName(buildFullName(user));
                }
                stripe.customers().update(customerId, updateBuilder.build());
                log.info("Synced customer {} for user {} (name {})", customerId, user.getId(),
                        (existingName == null || existingName.isBlank()) ? "stamped" : "preserved");
                return bc;
            } catch (InvalidRequestException e) {
                if (isNoSuchCustomerError(e)) {
                    log.warn("Customer {} n'existe plus, on va le recreer (user={})", customerId, user.getId());
                    bc.setProviderCustomerId(null);
                    billingCustomerRepository.save(bc);
                } else {
                    throw e;
                }
            }
        }
        return recreateStripeCustomer(user, bc);
    }

    private BillingCustomer recreateStripeCustomer(User user, BillingCustomer bc) throws StripeException {
        String nonce = nonceUtil.generateNonce(user.getId());

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(buildFullName(user))
                .putMetadata("userId", String.valueOf(user.getId()))
                .putMetadata("nonce", nonce)
                .build();
        Customer created = stripe.customers().create(params);
        bc.setProviderCustomerId(created.getId());
        
        
        return billingCustomerRepository.save(bc);
    }

    private String buildFullName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) return full;
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
        return user.getEmail();
    }

    private boolean isNoSuchCustomerError(InvalidRequestException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String code = e.getCode();
        return msg.contains("No such customer") || "resource_missing".equals(code);
    }

    /**
     * Construit les parametres de session Stripe avec un nonce de securite
     * Utilise le nonce comme client_reference_id au lieu d'exposer l'userId
     */
    // ========================================================================
    // V250/PR3 - PAYG one-time top-up checkout
    // ========================================================================

    /**
     * Tier → credit amount mapping. Matches the three Stripe price rows
     * seeded by V251 migration (cadence='payg_small'/'payg_medium'/'payg_large').
     * The Stripe price amount (USD) determines the upper bound of what the
     * platform expects to receive; the credit_amount here is the on-platform
     * credits granted on successful payment.
     *
     * <p><b>Source of truth contract</b>: this in-code map is the canonical
     * tier→credits resolver. {@link #createPaygCheckoutSession} reads it and
     * stuffs the resolved amount into {@code session.metadata.credit_amount}
     * at checkout creation; the webhook handler ({@code WebhookController.
     * parseAndGrantPaygTopup}) reads the same field back from the session.
     * The Stripe <i>Product</i> object also carries a {@code metadata.credits}
     * tag for dashboard / audit visibility, but it is NEVER read at runtime -
     * it can drift without affecting customer billing. If a future refactor
     * trusts Stripe product metadata over this map, customers will be
     * under-credited (the 5,000/25,000/50,000 values seeded in May 2026 are
     * still on the Dashboard side until ops re-patches them).
     *
     * <p>Pricing convention (decided 2026-05-19): 800 credits per USD = $1.25 /
     * 1k credits. Strictly more expensive per credit than any CREDIT_PACK tier
     * - pack tier 1 sits at $1.00 / 1k, pack tier 7+ at the floor $0.50 / 1k -
     * so a subscriber + pack is always rewarded over one-time PAYG (the
     * "no-commitment fee" model). Below that floor would undercut subs and
     * cannibalize MRR; above $1.25 / 1k starts to look punitive vs OpenAI / etc.
     *
     * <pre>
     *   small  = $10  → 8,000  credits (800 c/$)
     *   medium = $50  → 40,000 credits (800 c/$)
     *   large  = $100 → 80,000 credits (800 c/$)
     * </pre>
     */
    private static final java.util.Map<String, java.math.BigDecimal> PAYG_TIER_CREDITS = java.util.Map.of(
            "small",  new java.math.BigDecimal("8000"),
            "medium", new java.math.BigDecimal("40000"),
            "large",  new java.math.BigDecimal("80000"));

    /**
     * V250 - DTO surfaced by {@code GET /api/billing/payg-tiers}. The {@code configured}
     * flag tells the frontend whether the corresponding Stripe price has been
     * wired (V251 seed inserts {@code provider_price_id=NULL} placeholders that
     * ops must update with real {@code price_xxx} ids before the tier becomes
     * purchasable). The frontend uses this flag to gray out un-configured cards.
     */
    public record PaygTierView(String tier, long credits, long amountCents, String currency, boolean configured) {}

    /**
     * V250 - bucket-aware tier list for the wallet UI. Joins the in-code
     * credit grants (canonical, never null) with the on-DB Stripe price rows
     * seeded by V251 (cadence {@code payg_*}). A tier whose row is missing OR
     * whose {@code provider_price_id} is NULL surfaces as {@code configured=false}
     * so the frontend can render a "PAYG bientôt disponible" state instead of
     * letting the user click into a Stripe 503.
     */
    @Transactional(readOnly = true)
    public java.util.List<PaygTierView> getPaygTiers() {
        // Index priceRepository rows by cadence suffix (small/medium/large)
        java.util.Map<String, com.apimarketplace.auth.domain.Price> priceByTier = new java.util.HashMap<>();
        for (com.apimarketplace.auth.domain.Price p : priceRepository.findAllPaygTiersOrderedByPrice()) {
            String cadence = p.getCadence();
            String tierKey = (cadence != null && cadence.startsWith("payg_"))
                    ? cadence.substring("payg_".length()) : cadence;
            if (tierKey != null) priceByTier.putIfAbsent(tierKey, p);
        }

        // Iterate in canonical small → medium → large order so the UI cards
        // render predictably regardless of price-row insertion order.
        java.util.List<String> tierOrder = java.util.List.of("small", "medium", "large");
        java.util.List<PaygTierView> out = new java.util.ArrayList<>(tierOrder.size());
        for (String tier : tierOrder) {
            java.math.BigDecimal credits = PAYG_TIER_CREDITS.get(tier);
            if (credits == null) continue;
            com.apimarketplace.auth.domain.Price price = priceByTier.get(tier);
            long amountCents = price != null && price.getAmountCents() != null ? price.getAmountCents() : 0L;
            String currency = price != null && price.getCurrency() != null ? price.getCurrency() : "usd";
            boolean configured = price != null
                    && price.getProviderPriceId() != null
                    && !price.getProviderPriceId().isBlank();
            out.add(new PaygTierView(tier, credits.longValue(), amountCents, currency, configured));
        }
        return out;
    }

    /**
     * V250/PR3 - Create a Stripe Checkout Session in {@code mode=PAYMENT}
     * (one-time) for a PAYG credit top-up.
     *
     * <p>Unlike {@link #createCheckoutSession} which creates a recurring
     * subscription, this method creates a one-shot payment whose successful
     * completion triggers a {@code grantCredits(sourceType="PAYG_TOPUP")}
     * via {@code WebhookController.handleCheckoutCompleted}. The PAYG bucket
     * on the user's subscription receives the credits; sub-renewal cycles
     * do NOT reset it.
     *
     * <p>Pre-condition: the three Stripe prices for cadences {@code payg_small},
     * {@code payg_medium}, {@code payg_large} must be wired in the price
     * table (V251 inserts them with {@code provider_price_id=NULL}; V256 then
     * fills the column with TEST mode IDs, and a future LIVE migration will
     * overwrite with production IDs - see V256 header for the rollout plan).
     * If the price_id is unwired, this method throws - the startup health
     * check (PR3 follow-up) warns ops before the user clicks Buy.
     *
     * @param userId  recipient of the credits
     * @param tier    "small" / "medium" / "large"
     * @return Stripe Checkout Session URL for the frontend to redirect to
     */
    public String createPaygCheckoutSession(Long userId, String tier) throws Exception {
        if (userId == null) {
            throw new IllegalArgumentException("userId required");
        }
        if (tier == null || !PAYG_TIER_CREDITS.containsKey(tier)) {
            throw new IllegalArgumentException("Invalid PAYG tier: " + tier
                    + " (allowed: small, medium, large)");
        }

        BillingMDC.context(userId, null, "payg-checkout");
        BillingMDC.logStart(log, "Creating PAYG one-time checkout for tier {}", tier);

        java.math.BigDecimal creditAmount = PAYG_TIER_CREDITS.get(tier);
        String cadence = "payg_" + tier;
        String priceId = priceCacheService.getPriceId("PAYG", cadence)
                .filter(pid -> pid != null && !pid.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "PAYG price not configured for cadence=" + cadence
                                + ". Run the ops one-liner to wire provider_price_id "
                                + "after creating the price in Stripe Dashboard."));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        BillingCustomer bc = ensureValidStripeCustomer(user);
        String nonce = nonceUtil.generateNonce(userId);

        String successUrl = checkoutSuccessUrl + "&session_id={CHECKOUT_SESSION_ID}&payg=" + tier;
        String cancelUrl = checkoutCancelUrl;

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(bc.getProviderCustomerId())
                .setClientReferenceId(nonce)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                // Webhook routing keys - handleCheckoutCompleted dispatches on metadata.kind
                .putMetadata("kind", "payg_topup")
                .putMetadata("tier", tier)
                .putMetadata("credit_amount", creditAmount.toPlainString())
                .putMetadata("nonce", nonce)
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.AUTO)
                // automatic_tax with an existing customer requires customer_update.address=auto
                // so the address entered in Checkout is saved and used for tax calculation.
                // tax_id_collection additionally requires customer_update.name=auto (Stripe
                // overwrites the customer name with the business name entered with the tax ID).
                .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                        .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                        .setName(SessionCreateParams.CustomerUpdate.Name.AUTO)
                        .build())
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder()
                        .setEnabled(true)
                        .build())
                // EU B2B reverse charge: lets business customers enter their VAT number at
                // checkout - Stripe validates it and zero-rates the sale where applicable
                .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder()
                        .setEnabled(true)
                        .build())
                // payment-mode sessions produce no invoice by default - B2B customers
                // need one showing the reverse-charge note and their VAT ID
                .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder()
                        .setEnabled(true)
                        .build())
                .build();

        Session session = stripe.checkout().sessions().create(params);
        log.info("PAYG checkout session created (user={}, tier={}, amount={} credits, session={})",
                userId, tier, creditAmount, session.getId());
        return session.getUrl();
    }

    private SessionCreateParams buildSessionParams(
            String customerId,
            String priceId,
            String nonce,
            String normalizedPlanCode,
            String billingCustomerId,
            String successUrl,
            String cancelUrl,
            String creditPriceId,
            int creditQuantity,
            int creditTierIndex
                                                           ) {
        var builder = SessionCreateParams.builder()
                                  .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                                  .setCustomer(customerId)
                                  .setClientReferenceId(nonce)
                                  .addLineItem(SessionCreateParams.LineItem.builder()
                                                                           .setPrice(priceId)
                                                                           .setQuantity(1L)
                                                                           .build())
                                  .setSuccessUrl(successUrl)
                                  .setCancelUrl(cancelUrl)
                                  .putMetadata("nonce", nonce)
                                  .putMetadata("planCode", normalizedPlanCode)
                                  .putMetadata("billingCustomerId", billingCustomerId)
                                  .putMetadata("creditTierIndex", String.valueOf(creditTierIndex))
                                  .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                                                                                       .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                                                                                       .setShipping(SessionCreateParams.CustomerUpdate.Shipping.AUTO)
                                                                                       // required by tax_id_collection: Stripe saves the business
                                                                                       // name entered alongside the VAT number onto the customer
                                                                                       .setName(SessionCreateParams.CustomerUpdate.Name.AUTO)
                                                                                       .build())
                                  .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.AUTO)
                                  .setAutomaticTax(SessionCreateParams.AutomaticTax.builder()
                                                                                   .setEnabled(true)
                                                                                   .build())
                                  // EU B2B reverse charge: collect + validate the customer's VAT
                                  // number at checkout; zero-rates the sale where applicable
                                  .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder()
                                                                                          .setEnabled(true)
                                                                                          .build());

        // Add credit pack line item if quantity > 0
        if (creditPriceId != null && creditQuantity > 0) {
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                                                            .setPrice(creditPriceId)
                                                            .setQuantity((long) creditQuantity)
                                                            .build());
        }

        return builder.build();
    }

    public Map<String, Object> getDebugInfo() {
        Map<String, Object> debug = new HashMap<>();

        if (priceCacheService != null) {
            Map<String, String> cachedPrices = priceCacheService.getAllCachedPrices();
            debug.put("cachedPrices", cachedPrices);
            debug.put("cachedPricesSize", cachedPrices != null ? cachedPrices.size() : 0);
            debug.put("FREE_price", priceCacheService.getPriceId("FREE").orElse("N/A"));
            debug.put("STARTER_price", priceCacheService.getPriceId("STARTER").orElse("N/A"));
            debug.put("PRO_price", priceCacheService.getPriceId("PRO").orElse("N/A"));
            debug.put("ENTERPRISE_BASIC_price", priceCacheService.getPriceId("ENTERPRISE_BASIC").orElse("N/A"));
            debug.put("ENTERPRISE_STANDARD_price", priceCacheService.getPriceId("ENTERPRISE_STANDARD").orElse("N/A"));
            debug.put("ENTERPRISE_PREMIUM_price", priceCacheService.getPriceId("ENTERPRISE_PREMIUM").orElse("N/A"));
            debug.put("ENTERPRISE_ULTIMATE_price", priceCacheService.getPriceId("ENTERPRISE_ULTIMATE").orElse("N/A"));
            debug.put("PAYG_price", priceCacheService.getPriceId("PAYG").orElse("N/A"));
        }
        return debug;
    }

    private void createFreeSubscription(User user) {
        try {
            var freePlan = planRepository.findByCode("FREE");
            if (freePlan.isEmpty()) {
                log.error("Plan FREE introuvable");
                return;
            }
            var bcOpt = billingCustomerRepository.findByUserId(user.getId());
            if (bcOpt.isEmpty()) {
                log.error("BillingCustomer introuvable pour user {}", user.getId());
                return;
            }
            BillingCustomer bc = bcOpt.get();

            Subscription sub = new Subscription();
            sub.setBillingCustomer(bc);
            sub.setPlan(freePlan.get());
            sub.setStatus("active");
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            sub.setCancelAtPeriodEnd(false);
            sub.setCreatedAt(LocalDateTime.now());
            sub.setUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(sub);

            // Fallback FREE-creation path - sync storage quota to FREE allowance.
            quotaSyncer.syncAfterCommit(bc.getUser().getId(), freePlan.get());
        } catch (Exception e) {
            log.error("Erreur creation abonnement FREE: {}", e.getMessage(), e);
        }
    }

    @Data
    public static class ChangePlanResult {
        public final String providerSubscriptionId;
        public final String newPlanCode;
        public final boolean applyNow;
        public final long effectiveAtEpoch;
    }

    @Data
    public static class UpgradeResult {
        private final String providerSubscriptionId;
        private final String oldPlanCode;
        private final String newPlanCode;
        private final Long newLocalSubscriptionId;
    }

    /**
     * Annule toutes les autres subscriptions Stripe « vivantes » du meme customer,
     * sauf celle a conserver (keepSubscriptionId).
     * - atPeriodEnd = true  → set cancel_at_period_end=true
     * - atPeriodEnd = false → annulation immediate
     * <p>
     * Met aussi a jour la DB locale si la sub existe chez toi.
     *
     * @return nombre de subscriptions annulees (ou marquees cancel_at_period_end)
     */
    /**
     * Annule toutes les autres subscriptions Stripe « vivantes » du meme customer,
     * sauf celle a conserver (keepSubscriptionId).
     */
    public int cancelOtherStripeSubscriptions(String stripeCustomerId,
                                              String keepSubscriptionId,
                                              boolean atPeriodEnd) throws StripeException {
        int canceled = 0;
        String startingAfter = null;

        while (true) {
            var listParamsBuilder = com.stripe.param.SubscriptionListParams.builder()
                                                                           .setCustomer(stripeCustomerId)
                                                                           .setLimit(100L);

            if (startingAfter != null) {
                listParamsBuilder.setStartingAfter(startingAfter);
            }

            com.stripe.model.StripeCollection<com.stripe.model.Subscription> page =
                    stripe.subscriptions().list(listParamsBuilder.build());

            var data = page.getData();
            if (data == null || data.isEmpty()) break;

            for (var s : data) {
                if (keepSubscriptionId != null && keepSubscriptionId.equals(s.getId())) continue;

                String st = s.getStatus();
                boolean alive = "active".equals(st) || "trialing".equals(st) ||
                                "incomplete".equals(st) || "past_due".equals(st) || "unpaid".equals(st);
                if (!alive) continue;

                try {
                    if (atPeriodEnd) {
                        var upd = com.stripe.param.SubscriptionUpdateParams.builder()
                                                                           .setCancelAtPeriodEnd(true)
                                                                           .build();
                        stripe.subscriptions().update(s.getId(), upd);
                        log.info("Marque cancel_at_period_end=true sur sub {}", s.getId());
                    } else {
                        stripe.subscriptions().cancel(s.getId());
                        log.info("Annule immediatement sub {}", s.getId());
                    }

                    // MAJ locale si on a cette sub en base
                    subscriptionRepository.findByProviderSubscriptionId(s.getId()).ifPresent(local -> {
                        local.setStatus(atPeriodEnd ? "active" : "canceled");
                        local.setCancelAtPeriodEnd(atPeriodEnd);
                        if (!atPeriodEnd) {
                            local.setCurrentPeriodEnd(java.time.LocalDateTime.now());
                        }
                        local.setUpdatedAt(java.time.LocalDateTime.now());
                        subscriptionRepository.save(local);
                    });

                    canceled++;
                } catch (Exception ex) {
                    log.error("Annulation sub {} echouee: {}", s.getId(), ex.getMessage(), ex);
                }
            }

            if (Boolean.TRUE.equals(page.getHasMore())) {
                startingAfter = data.get(data.size() - 1).getId();
            } else {
                break;
            }
        }
        return canceled;
    }

    private static boolean isAliveStatus(String st) {
        if (st == null) return false;
        return switch (st) {
            case "active", "trialing", "incomplete", "past_due", "unpaid" -> true;
            default -> false; // canceled, incomplete_expired, paused, etc.
        };
    }

    private boolean isNoSuchSubscriptionError(InvalidRequestException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String code = e.getCode();
        return msg.contains("No such subscription") || "resource_missing".equals(code);
    }

    /**
     * Marque la ligne locale comme annulee (sub Stripe inexistante/annulee) et libere providerSubscriptionId.
     *
     * <p>V250 two-bucket reconciliation: if the canceled sub holds a positive
     * PAYG balance, transfer it to the user's next live subscription so the
     * paid top-up doesn't get stranded on an unreachable row. This mirrors the
     * carry-over the {@code onSubscriptionUpsert} sibling-cancel loop performs
     * for the active-upgrade path. Same money-loss shape, different trigger:
     * here the cancel comes from a Stripe-side mutation (admin Dashboard,
     * "no such subscription" reconciliation), not a local plan upgrade.
     *
     * <p>When no other live sub exists (the user just left), the PAYG balance
     * is kept on the row and a WARN is emitted with the user_id + amount so
     * ops can manually reconcile (refund on Stripe / migrate to a new sub).
     * Wiping the balance silently would be the worst outcome.
     */
    private void reconcileLocalCanceled(Subscription local, String reason) {
        LocalDateTime now = LocalDateTime.now();

        java.math.BigDecimal payg = local.getPaygRemainingCredits();
        if (payg != null && payg.signum() > 0) {
            Long userId = local.getBillingCustomer() != null && local.getBillingCustomer().getUser() != null
                    ? local.getBillingCustomer().getUser().getId()
                    : null;
            Subscription target = userId == null ? null
                    : subscriptionRepository.findActiveByUserId(userId)
                            .filter(s -> !s.getId().equals(local.getId()))
                            .orElse(null);
            if (target != null) {
                java.math.BigDecimal current = target.getPaygRemainingCredits() != null
                        ? target.getPaygRemainingCredits() : java.math.BigDecimal.ZERO;
                target.setPaygRemainingCredits(current.add(payg));
                target.setUpdatedAt(now);
                subscriptionRepository.save(target);
                local.setPaygRemainingCredits(java.math.BigDecimal.ZERO);
                log.info("Carried {} PAYG credits from canceled local sub {} to active sub {} (user={}, reason={})",
                        payg, local.getId(), target.getId(), userId, reason);
            } else {
                // No live target - surface for ops so the balance is not silently orphaned.
                log.warn("Local sub {} canceled with {} PAYG credits stranded (user={}, reason={}). " +
                        "No other active subscription to carry over to - manual reconciliation required " +
                        "(refund via Stripe or migrate to a new sub).",
                        local.getId(), payg, userId, reason);
            }
        }

        local.setStatus("canceled");
        local.setCancelAtPeriodEnd(true);
        local.setCurrentPeriodEnd(now);
        local.setUpdatedAt(now);
        // IMPORTANT si colonne unique côte DB
        local.setProviderSubscriptionId(null);
        subscriptionRepository.save(local);
        log.warn("Local sub {} reconciliee comme canceled ({}).", local.getId(), reason);

        // V311: this Stripe-side cancel reverts the owner toward the FREE workspace cap without
        // flowing through onSubscriptionUpsert - pause any over-cap workspaces. Never fails the
        // cancel-reconcile (the workspace pause is best-effort here).
        if (organizationService != null) {
            try {
                Long ownerId = local.getBillingCustomer() != null && local.getBillingCustomer().getUser() != null
                        ? local.getBillingCustomer().getUser().getId()
                        : null;
                if (ownerId != null) {
                    organizationService.reconcileWorkspacePauseState(ownerId);
                }
            } catch (Exception e) {
                log.error("Workspace pause reconcile failed after local cancel reconcile (sub {}): {}",
                        local.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Cancels the user's subscription at the end of the current billing period.
     * Sets cancel_at_period_end=true on Stripe, updates local DB, and logs the event.
     *
     * @param userId   The user ID
     * @param reason   The cancellation reason
     * @param feedback Optional user feedback
     * @return Map with success status and effective cancellation date
     */
    public Map<String, Object> cancelSubscriptionAtPeriodEnd(Long userId, String reason, String feedback) throws StripeException {
        BillingMDC.context(userId, null, "cancel");
        BillingMDC.logStart(log, "Cancelling subscription at period end, reason={}", reason);

        Subscription local = getActiveSubscription(userId);
        String providerSubId = local.getProviderSubscriptionId();

        if (providerSubId == null || providerSubId.isBlank()) {
            throw new IllegalStateException("No Stripe subscription linked to this user");
        }

        // Update Stripe: set cancel_at_period_end = true
        var upd = SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(true)
                .build();
        var updatedStripeSub = stripe.subscriptions().update(providerSubId, upd);

        // Update local DB
        local.setCancelAtPeriodEnd(true);
        local.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(local);

        log.info("Subscription {} marked for cancellation at period end (user={})", providerSubId, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("effectiveDate", local.getCurrentPeriodEnd() != null
                ? local.getCurrentPeriodEnd().toString() : null);
        return result;
    }

    /**
     * Reactivates a subscription that was scheduled for cancellation.
     * Sets cancel_at_period_end=false on Stripe and updates local DB.
     *
     * @param userId The user ID
     * @return Map with success status
     */
    public Map<String, Object> reactivateSubscription(Long userId) throws StripeException {
        BillingMDC.context(userId, null, "reactivate");
        BillingMDC.logStart(log, "Reactivating subscription for user {}", userId);

        Subscription local = getActiveSubscription(userId);
        String providerSubId = local.getProviderSubscriptionId();

        if (providerSubId == null || providerSubId.isBlank()) {
            throw new IllegalStateException("No Stripe subscription linked to this user");
        }

        if (!Boolean.TRUE.equals(local.getCancelAtPeriodEnd())) {
            throw new IllegalStateException("Subscription is not scheduled for cancellation");
        }

        // Update Stripe: set cancel_at_period_end = false
        var upd = SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(false)
                .build();
        stripe.subscriptions().update(providerSubId, upd);

        // Update local DB
        local.setCancelAtPeriodEnd(false);
        local.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(local);

        log.info("Subscription {} reactivated (user={})", providerSubId, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * Decode un nonce pour recuperer l'ID utilisateur
     * Utile dans les webhooks Stripe ou autres endroits où nous recevons un nonce
     * 
     * @param nonce Le nonce a decoder
     * @return L'ID utilisateur ou null si le nonce est invalide
     */
    public Long decodeUserIdFromNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            log.warn("Nonce is null or empty");
            return null;
        }
        
        return nonceUtil.decodeNonce(nonce);
    }
    
    /**
     * Valide qu'un nonce correspond a un utilisateur donne
     * 
     * @param nonce Le nonce a valider
     * @param userId L'ID utilisateur attendu
     * @return true si le nonce est valide pour cet utilisateur
     */
    public boolean validateNonceForUser(String nonce, Long userId) {
        return nonceUtil.validateNonce(nonce, userId);
    }


}
