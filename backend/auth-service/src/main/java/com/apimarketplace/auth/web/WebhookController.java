// src/main/java/com/apimarketplace/auth/web/WebhookController.java
package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.service.CreditAttributionService;
import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.service.SubscriptionService;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.StripeClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.apimarketplace.auth.service.util.StripeSubscriptionPeriod;
import com.apimarketplace.auth.service.util.BillingMDC;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/webhooks")
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    private final ObjectMapper objectMapper;
    private final BillingEventRepository billingEventRepository;
    private final SubscriptionService subscriptionService;
    private final BillingCustomerRepository billingCustomerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PriceCacheService priceCacheService;
    private final CreditAttributionService creditAttributionService;
    private final PendingCreditUpgradeRepository pendingCreditUpgradeRepository;
    private final String webhookSecret;
    private final StripeBillingService stripeBillingService; // <-- used for the safety net
    private final NonceUtil nonceUtil;
    private final StripeClient stripeClient; // SDK 31+ best practice: use injected client

    // PR9: when an OWNER's subscription changes, fan-out the gateway-cache bust so all members
    // of the OWNER's orgs see the new tier on their next request (instead of stale-up-to-5min).
    // The fan-out logic lives in the shared SubscriptionCacheBuster so the admin comp-plan grant
    // uses the EXACT same pipe. Optional injection so tests / CE mode without it keep working.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.service.SubscriptionCacheBuster subscriptionCacheBuster;

    // Referral: qualify a redeemer's conversion on their first paid invoice and
    // claw back on refund/dispute. Optional so legacy test ctors and any context
    // without it keep working (the new paths null-guard on it).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.service.RewardService rewardService;

    public WebhookController(
            BillingEventRepository billingEventRepository,
            SubscriptionService subscriptionService,
            BillingCustomerRepository billingCustomerRepository,
            SubscriptionRepository subscriptionRepository,
            ObjectMapper objectMapper,
            PlanRepository planRepository,
            PriceCacheService priceCacheService,
            CreditAttributionService creditAttributionService,
            PendingCreditUpgradeRepository pendingCreditUpgradeRepository,
            StripeBillingService stripeBillingService,
            NonceUtil nonceUtil,
            StripeClient stripeClient,
            @Value("${billing.stripe.webhookSecret}") String webhookSecret
                            ) {
        this.billingEventRepository = billingEventRepository;
        this.subscriptionService = subscriptionService;
        this.billingCustomerRepository = billingCustomerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
        this.planRepository = planRepository;
        this.priceCacheService = priceCacheService;
        this.creditAttributionService = creditAttributionService;
        this.pendingCreditUpgradeRepository = pendingCreditUpgradeRepository;
        this.webhookSecret = webhookSecret;
        this.stripeBillingService = stripeBillingService;
        this.nonceUtil = nonceUtil;
        this.stripeClient = stripeClient;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(HttpServletRequest request) {
        String payload;
        try {
            payload = readRequestBody(request);
        } catch (IOException e) {
            logger.error("Unable to read request body", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid payload");
        }

        String sigHeader = request.getHeader("Stripe-Signature");
        if (sigHeader == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature");
        }

        final Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            logger.warn("Invalid webhook signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch (Exception e) {
            logger.error("Failed to parse Stripe event", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid event");
        }

        try {
            if (billingEventRepository.existsByEventId(event.getId())) {
                logger.info("Event {} already processed, skipping", event.getId());
                return ResponseEntity.ok("OK");
            }
            var jsonNode = objectMapper.readTree(payload);
            billingEventRepository.save(new BillingEvent("stripe", event.getId(), event.getType(), jsonNode));
        } catch (Exception e) {
            logger.warn("Race on billing event insert for id {}", event.getId(), e);
            return ResponseEntity.ok("OK");
        }

        try {
            dispatch(event);
        } catch (Exception e) {
            logger.error("Error while processing event {}", event.getId(), e);
        }

        return ResponseEntity.ok("OK");
    }

    private void dispatch(Event event) {
        String type = event.getType();
        
        // MDC logging for webhook traceability
        BillingMDC.webhookContext(event.getId(), type);
        BillingMDC.logStart(logger, "Processing webhook event: {}", type);
        
        EventDataObjectDeserializer deser = event.getDataObjectDeserializer();

        deser.getObject().ifPresentOrElse((StripeObject obj) -> {
            switch (type) {
                case "checkout.session.completed" -> handleCheckoutCompleted((Session) obj);

                case "customer.subscription.created",
                     "customer.subscription.updated" -> {
                    try {
                        handleSubscriptionUpsert((Subscription) obj, event.getId());
                    } catch (StripeException e) {
                        throw new RuntimeException(e);
                    }
                }

                case "customer.subscription.deleted" -> handleSubscriptionDeleted((Subscription) obj, event);
                case "customer.deleted" -> handleCustomerDeleted((Customer) obj);

                case "invoice.paid" -> handleInvoicePaid((Invoice) obj);

                case "invoice.payment_succeeded" -> {
                    if (obj instanceof Invoice inv) {
                        handleInvoicePaymentSucceeded(inv);
                    } else {
                        handleRawInvoiceLike(event); // fallback if not typed
                    }
                }

                case "invoice.payment_failed" -> {
                    if (obj instanceof Invoice inv) {
                        handleInvoicePaymentFailed(inv);
                    } else {
                        logger.warn("invoice.payment_failed received but not typed Invoice; ignoring (event={})",
                                event.getId());
                    }
                }

                // Subscription Schedule events (for scheduled downgrades)
                case "subscription_schedule.created" -> handleSubscriptionScheduleCreated(event);
                case "subscription_schedule.updated" -> handleSubscriptionScheduleUpdated(event);
                case "subscription_schedule.released" -> handleSubscriptionScheduleReleased(event);
                case "subscription_schedule.completed" -> handleSubscriptionScheduleCompleted(event);
                case "subscription_schedule.canceled" -> handleSubscriptionScheduleCanceled(event);

                case "charge.refunded" -> handleChargeRefunded((com.stripe.model.Charge) obj);
                case "charge.dispute.created" -> handleDisputeCreated((com.stripe.model.Dispute) obj);

                default -> {
                    if ("invoice_payment.paid".equals(type)) {
                        handleRawInvoiceLike(event); // non-typed event (new object)
                    } else if ("charge.succeeded".equals(type)) {
                        logger.info("charge.succeeded received (ignored for provisioning)");
                    } else {
                        logger.info("Unhandled event type: {}", type);
                    }
                }
            }
        }, () -> {
            // RAW fallbacks when the SDK cannot deserialize the object
            switch (type) {
                case "checkout.session.completed" -> handleCheckoutCompletedRaw(event);
                case "customer.subscription.created", "customer.subscription.updated" -> handleSubscriptionUpsertRaw(event);
                case "customer.subscription.deleted" -> handleSubscriptionDeletedRaw(event);
                case "customer.deleted" -> handleCustomerDeletedRaw(event);
                case "invoice.paid" -> handleInvoicePaidRaw(event);
                case "invoice.payment_succeeded", "invoice_payment.paid" -> handleRawInvoiceLike(event);
                case "charge.refunded" -> handleChargeRefundedRaw(event);
                case "charge.dispute.created" -> handleDisputeCreatedRaw(event);
                default -> logger.warn("Deserializer empty for {}. Raw: {}", type, event.getData().getObject());
            }
        });
    }


    private void handleSubscriptionDeletedRaw(Event event) {
        try {
            String subId = null;
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                if ("subscription".equals(json.path("object").asText())) {
                    subId = json.path("id").asText(null);
                }
            }

            if (subId == null || subId.isBlank()) {
                logger.warn("Unable to extract subscription id from RAW event {}", event.getId());
                return;
            }

            // Attempt to retrieve the full subscription (useful for expand)
            try {
                var rp = SubscriptionRetrieveParams.builder()
                                                   .addExpand("items.data.price")
                                                   .build();
                Subscription sub = stripeClient.subscriptions().retrieve(subId, rp);
                handleSubscriptionDeleted(sub, event); // reuse the typed handler
            } catch (Exception e) {
                logger.warn("Retrieve failed for sub {}: {} - fallback to direct local cancellation.",
                            subId, e.getMessage());
                // Fallback: cancel locally by id (idempotent on service side)
                subscriptionService.cancelSubscription(subId);
            }
        } catch (Exception e) {
            logger.error("Error in handleSubscriptionDeletedRaw: {}", e.getMessage(), e);
        }
    }


// src/main/java/com/apimarketplace/auth/web/WebhookController.java

    private void handleRawInvoiceLike(Event event) {
        try {
            String invoiceId = null;
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                var objectType = json.path("object").asText();
                if ("invoice".equals(objectType)) {
                    invoiceId = json.path("id").asText(null);
                } else if ("invoice_payment".equals(objectType)) {
                    invoiceId = json.path("invoice").asText(null);
                }
            }

            if (invoiceId == null || invoiceId.isBlank()) {
                logger.warn("Unable to extract invoice id from RAW event {}", event.getId());
                return;
            }

            Invoice inv = stripeClient.invoices().retrieve(invoiceId);
            logger.info("RAW-> invoice {} status={}, billing_reason={}, customer={}",
                        inv.getId(), inv.getStatus(), inv.getBillingReason(), inv.getCustomer());

            // Provision ONLY for subscription creation
            if (!"subscription_create".equals(inv.getBillingReason())) {
                logger.info("Invoice {} not related to subscription creation ({}), no provisioning.",
                            inv.getId(), inv.getBillingReason());
                return;
            }

            // 1) Try via subscription - SDK 31+ uses parent.subscription_details
            String subId = null;
            try {
                // New SDK 31+ API: parent.subscription_details.subscription
                if (inv.getParent() != null && 
                    "subscription_details".equals(inv.getParent().getType()) &&
                    inv.getParent().getSubscriptionDetails() != null) {
                    subId = inv.getParent().getSubscriptionDetails().getSubscription();
                }
            } catch (NoSuchMethodError | Exception e) {
                // Fallback for older SDK versions (just in case)
                logger.debug("Fallback: getParent() not available, attempting via customer");
            }
            
            if (subId != null && !subId.isBlank()) {
                // Guard: skip if local subscription already exists (provisioned by customer.subscription.created)
                if (subscriptionService.existsByProviderSubscriptionId(subId)) {
                    logger.info("Local subscription already exists for {}, skipping RAW invoice provisioning", subId);
                    return;
                }
                var rp = SubscriptionRetrieveParams.builder().addExpand("items.data.price").build();
                Subscription sub = stripeClient.subscriptions().retrieve(subId, rp);
                handleSubscriptionUpsert(sub, "inv_" + inv.getId());
                return;
            }

            // 2) Otherwise, last subscription of the customer (best available lead on Stripe side)
            String customerId = inv.getCustomer();
            if (customerId != null) {
                var params = SubscriptionListParams.builder()
                                                   .setCustomer(customerId)
                                                   .setLimit(1L)
                                                   .build();
                var list = stripeClient.subscriptions().list(params);
                if (list != null && !list.getData().isEmpty()) {
                    var sub = list.getData().get(0);
                    // Guard: skip if local subscription already exists
                    if (subscriptionService.existsByProviderSubscriptionId(sub.getId())) {
                        logger.info("Local subscription already exists for {}, skipping RAW invoice provisioning", sub.getId());
                        return;
                    }
                    handleSubscriptionUpsert(sub, "inv_" + inv.getId());
                    return;
                }
            }

            logger.warn("No subscription found for invoice {}", inv.getId());

        } catch (Exception e) {
            logger.error("Error in handleRawInvoiceLike: {}", e.getMessage(), e);
        }
    }



    /**
     * Fallback for the customer.deleted event when the SDK cannot deserialize the object.
     * Extracts the customer ID from the raw payload and applies the same logic
     * as handleCustomerDeleted(..): detach the Stripe customer, cancel/close
     * live local subs, and downgrade quotas to FREE.
     */
    private void handleCustomerDeletedRaw(Event event) {
        try {
            String customerId = null;

            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                // Normally object == "customer" and we have an "id" field
                if ("customer".equals(json.path("object").asText())) {
                    customerId = json.path("id").asText(null);
                }
                // Ultra-cautious fallback if a different wrapper arrives
                if ((customerId == null || customerId.isBlank()) && json.has("customer")) {
                    customerId = json.path("customer").asText(null);
                }
            }

            if (customerId == null || customerId.isBlank()) {
                logger.warn("RAW customer.deleted: unable to extract customer id (event={})", event.getId());
                return;
            }

            logger.info("Stripe customer deleted (RAW): {}", customerId);

            final String finalCustomerId = customerId;
            billingCustomerRepository.findByProviderCustomerId(finalCustomerId).ifPresentOrElse(bc -> {
                // Detach Stripe customer locally
                bc.setProviderCustomerId(null);
                billingCustomerRepository.save(bc);

                // Close all local subs still alive
                var subs = subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(bc.getId());
                var now = LocalDateTime.now();
                boolean changed = false;
                for (var s : subs) {
                    String st = s.getStatus();
                    boolean alive = "active".equals(st) || "trialing".equals(st) ||
                                    "past_due".equals(st) || "incomplete".equals(st);
                    if (!alive) continue;

                    s.setStatus("canceled");
                    s.setCancelAtPeriodEnd(true);
                    s.setUpdatedAt(now);
                    changed = true;
                    logger.info("Local subscription {} canceled due to customer deletion {}", s.getId(), finalCustomerId);
                }
                if (changed) {
                    subscriptionRepository.saveAll(subs);
                }

                // Plan transition is owned by SubscriptionService - quota mutation
                // here is a no-op since cycle-counter quotas were retired (CreditService
                // wallet handles all real billing). Logging only.
                User user = bc.getUser();
                logger.info("Customer deletion processed for user {} (plan→FREE handled via subscription update)", user.getId());

                // V311: customer deletion drops the owner to the FREE workspace cap (no active sub
                // left) WITHOUT flowing through onSubscriptionUpsert - pause any over-cap workspaces.
                subscriptionService.reconcileWorkspacesAfterPlanLoss(user.getId());

                // (Optional) If you have a force reauth mechanism, trigger it here.
                // Example if you have a UserRepository and a forceReauthAt field:
                // user.setForceReauthAt(LocalDateTime.now());
                // userRepository.save(user);
                // Otherwise, let the client detect 401/invalid_token on the API side.
            }, () -> logger.warn("No BillingCustomer found for deleted Stripe customer {}", finalCustomerId));

        } catch (Exception e) {
            logger.error("Error in handleCustomerDeletedRaw: {}", e.getMessage(), e);
        }
    }


    private void handleCheckoutCompleted(Session session) {
        logger.info("Checkout completed: session={}, customer={}, subscription={}",
                    session.getId(), session.getCustomer(), session.getSubscription());

        // Decode the nonce from client_reference_id to retrieve the userId
        String nonce = session.getClientReferenceId();
        if (nonce != null && !nonce.isEmpty()) {
            Long userId = nonceUtil.decodeNonce(nonce);
            if (userId != null) {
                logger.info("Checkout completed for user {} (decoded from nonce: {})", userId, nonce);

                // Record the checkout event with the decoded userId
                try {
                    ObjectNode checkoutEventPayload = objectMapper.createObjectNode();
                    checkoutEventPayload.put("sessionId", session.getId());
                    checkoutEventPayload.put("customerId", session.getCustomer());
                    checkoutEventPayload.put("subscriptionId", session.getSubscription());
                    checkoutEventPayload.put("userId", userId);
                    checkoutEventPayload.put("nonce", nonce);
                    checkoutEventPayload.put("action", "checkout_session_completed");

                    BillingEvent checkoutEvent = new BillingEvent(
                        "stripe",
                        "checkout_completed_" + session.getId(),
                        "checkout.session.completed",
                        checkoutEventPayload
                    );
                    billingEventRepository.save(checkoutEvent);
                } catch (Exception e) {
                    logger.warn("Failed to save checkout completed event: {}", e.getMessage());
                }

                // V250/PR3 - PAYG one-time top-up dispatch.
                // Stripe mode=PAYMENT checkouts (createPaygCheckoutSession) carry
                // metadata.kind="payg_topup". Subscription checkouts (mode=SUBSCRIPTION)
                // have no "kind" metadata and fall through to the standard
                // customer.subscription.* provisioning path.
                java.util.Map<String, String> metadata = session.getMetadata();
                if (metadata != null && "payg_topup".equals(metadata.get("kind"))) {
                    parseAndGrantPaygTopup(userId, session.getId(),
                            metadata.get("credit_amount"), metadata.get("tier"));
                    return;  // do NOT wait for customer.subscription.* - none will fire for mode=PAYMENT
                }
            } else {
                logger.warn("Failed to decode nonce from checkout session: {}", nonce);
            }
        } else {
            logger.warn("No nonce found in checkout session client_reference_id");
        }

        // We wait for customer.subscription.* to provision
    }

    /**
     * V250/PR3 - Shared PAYG one-time top-up grant path used by BOTH the typed
     * {@link #handleCheckoutCompleted(Session)} branch and the RAW
     * {@link #handleCheckoutCompletedRaw(Event)} fallback. Previously these
     * two paths each inlined the same credit_amount blank-check / BigDecimal
     * parse / signum-check / grant / catch sequence - the duplicate was a
     * silent drift vector (audit M4: a fix on the typed side could fail to
     * land on the RAW side and one Stripe SDK deserialization quirk away from
     * money loss).
     *
     * <p>Reads {@code credit_amount} (raw string from session metadata) and
     * {@code tier}, validates, and delegates to
     * {@code CreditAttributionService.grantPaygTopup} which routes the grant
     * onto {@code subscription.payg_remaining_credits}.
     *
     * <p>Idempotent via the underlying {@code credit_ledger.source_id} unique
     * constraint - a Stripe replay (same session id) is a no-op skip.
     *
     * <p>On successful grant, fans out the gateway-cache invalidation for the
     * user and every org they own (PR9). Without this the user's wallet may
     * stay stale-up-to-5min after a top-up that just cleared a delinquency,
     * blocking new reservations until the cache TTL elapses.
     */
    private void parseAndGrantPaygTopup(Long userId, String sessionId,
                                         String creditAmountStr, String tier) {
        if (creditAmountStr == null || creditAmountStr.isBlank()) {
            logger.warn("PAYG top-up session {} missing credit_amount metadata - skipping grant",
                    sessionId);
            return;
        }
        java.math.BigDecimal amount;
        try {
            amount = new java.math.BigDecimal(creditAmountStr);
        } catch (NumberFormatException e) {
            logger.error("PAYG top-up session {} has invalid credit_amount metadata: {}",
                    sessionId, creditAmountStr);
            return;
        }
        if (amount.signum() <= 0) {
            logger.warn("PAYG top-up session {} has non-positive credit_amount={} - skipping",
                    sessionId, amount);
            return;
        }

        try {
            creditAttributionService.grantPaygTopup(userId, amount, sessionId, tier);
            logger.info("PAYG top-up granted: user={}, tier={}, amount={}, session={}",
                    userId, tier, amount, sessionId);
            // PR9 - bust the gateway cache so a freshly-topped-up user whose
            // delinquency just cleared sees their balance on the next call
            // instead of waiting for the 5min TTL.
            fanOutCacheBustForSubscriptionChange(userId, "checkout.payg_topup");
        } catch (Exception e) {
            // Defensive log - grantPaygTopup itself catches the unique-constraint
            // duplicate case via existsBySourceId before insert. Any exception here
            // is unexpected (DB outage, etc.) and we want it visible without
            // failing the whole webhook (Stripe would retry the entire event).
            logger.error("PAYG top-up grant failed: user={}, tier={}, session={}, error={}",
                    userId, tier, sessionId, e.getMessage(), e);
        }
    }

    private void handleCheckoutCompletedRaw(Event event) {
        try {
            String sessionId = null;
            String nonce = null;
            String kind = null;
            String tier = null;
            String creditAmountStr = null;
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                if ("checkout.session".equals(json.path("object").asText())) {
                    sessionId = json.path("id").asText(null);
                    nonce = json.path("client_reference_id").asText(null);
                    var metadata = json.path("metadata");
                    if (metadata != null && metadata.isObject()) {
                        kind = metadata.path("kind").asText(null);
                        tier = metadata.path("tier").asText(null);
                        creditAmountStr = metadata.path("credit_amount").asText(null);
                    }
                }
            }

            if (sessionId == null || sessionId.isBlank()) {
                logger.warn("Unable to extract session id from RAW event {}", event.getId());
                return;
            }

            logger.info("Checkout completed (RAW): session={}", sessionId);

            // V250/PR3 - PAYG one-time top-up dispatch on the RAW path. Mirrors
            // the typed-path branch in handleCheckoutCompleted by delegating to
            // the SAME parseAndGrantPaygTopup helper, so a Stripe SDK
            // deserialization failure does NOT silently drop a money-bearing
            // webhook. The pre-helper version inlined the validation and was a
            // copy-paste drift vector (audit M4): fixing one path without the
            // other was one bad commit away from real money loss.
            if ("payg_topup".equals(kind) && nonce != null && !nonce.isBlank()) {
                Long userId = nonceUtil.decodeNonce(nonce);
                if (userId == null) {
                    logger.warn("PAYG top-up RAW session {} has invalid nonce - skipping grant", sessionId);
                    return;
                }
                parseAndGrantPaygTopup(userId, sessionId, creditAmountStr, tier);
                return;  // mode=PAYMENT: no customer.subscription.* event will follow
            }

            // We wait for customer.subscription.* to provision
        } catch (Exception e) {
            logger.error("Error in handleCheckoutCompletedRaw: {}", e.getMessage(), e);
        }
    }

    private void handleSubscriptionUpsertRaw(Event event) {
        try {
            String subscriptionId = null;
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                if ("subscription".equals(json.path("object").asText())) {
                    subscriptionId = json.path("id").asText(null);
                }
            }

            if (subscriptionId == null || subscriptionId.isBlank()) {
                logger.warn("Unable to extract subscription id from RAW event {}", event.getId());
                return;
            }

            // Retrieve the full subscription with expand
            try {
                var rp = SubscriptionRetrieveParams.builder()
                                                   .addExpand("items.data.price")
                                                   .build();
                Subscription sub = stripeClient.subscriptions().retrieve(subscriptionId, rp);
                handleSubscriptionUpsert(sub, event.getId());
            } catch (Exception e) {
                logger.error("Error retrieving subscription {}: {}", subscriptionId, e.getMessage(), e);
            }
        } catch (Exception e) {
            logger.error("Error in handleSubscriptionUpsertRaw: {}", e.getMessage(), e);
        }
    }

    private void handleInvoicePaidRaw(Event event) {
        try {
            String invoiceId = null;
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                if ("invoice".equals(json.path("object").asText())) {
                    invoiceId = json.path("id").asText(null);
                }
            }

            if (invoiceId == null || invoiceId.isBlank()) {
                logger.warn("Unable to extract invoice id from RAW event {}", event.getId());
                return;
            }

            Invoice inv = stripeClient.invoices().retrieve(invoiceId);
            handleInvoicePaid(inv);
        } catch (Exception e) {
            logger.error("Error in handleInvoicePaidRaw: {}", e.getMessage(), e);
        }
    }

    private void handleSubscriptionUpsert(Subscription sub, String eventId) throws StripeException {
        String subscriptionId = sub.getId();
        String status = sub.getStatus();

        // SDK 31+: use the utility to retrieve periods from SubscriptionItem
        StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(sub);
        LocalDateTime startAt = period.start();
        LocalDateTime endAt = period.end();

        // Decode the nonce from the Stripe customer metadata
        Long decodedUserId = null;
        String nonce = null;
        
        try {
            // Retrieve the Stripe customer to get the nonce from its metadata
            String customerId = sub.getCustomer();
            if (customerId != null) {
                var customer = stripeClient.customers().retrieve(customerId);
                if (customer != null && customer.getMetadata() != null) {
                    nonce = customer.getMetadata().get("nonce");
                    if (nonce != null && !nonce.isEmpty()) {
                        decodedUserId = nonceUtil.decodeNonce(nonce);
                        if (decodedUserId != null) {
                            logger.info("Decoded userId {} from nonce {} in customer metadata", decodedUserId, nonce);
                        } else {
                            logger.warn("Failed to decode nonce {} from customer metadata", nonce);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve nonce from customer metadata for subscription {}: {}", subscriptionId, e.getMessage());
        }
        
        if (decodedUserId == null) {
            logger.warn("No valid nonce found in customer metadata for subscription {}, falling back to customer lookup", subscriptionId);
        }

        Long planId = null;

        // 1) Metadata on the subscription (pushed by Checkout)
        if (sub.getMetadata() != null && sub.getMetadata().get("plan_id") != null) {
            try {
                planId = Long.valueOf(sub.getMetadata().get("plan_id"));
            } catch (NumberFormatException ignored) { }
        }

        // 2) Iterate subscription items to identify base plan vs credit pack
        String stripePriceId = null;
        String creditStripePriceId = null;
        int creditQuantity = 0;

        try {
            var rp = SubscriptionRetrieveParams.builder()
                                               .addExpand("items.data.price")
                                               .build();
            var full = stripeClient.subscriptions().retrieve(sub.getId(), rp);

            if (full.getItems() != null && !full.getItems().getData().isEmpty()) {
                for (var item : full.getItems().getData()) {
                    var price = item.getPrice();
                    if (price == null) continue;

                    if (priceCacheService.isCreditPackPrice(price.getId())) {
                        // This is the credit pack item
                        creditStripePriceId = price.getId();
                        creditQuantity = item.getQuantity() != null ? item.getQuantity().intValue() : 0;
                    } else {
                        // This is the base plan item
                        stripePriceId = price.getId();

                        // Resolve planId from metadata or price ID
                        if (planId == null && price.getMetadata() != null) {
                            String mid = price.getMetadata().get("plan_id");
                            if (mid != null) {
                                try { planId = Long.valueOf(mid); } catch (NumberFormatException ignored) { }
                            }
                        }
                        if (planId == null) {
                            String planCode = resolvePlanCodeFromPriceId(price.getId());
                            if (planCode != null) {
                                var planOpt = planRepository.findByCode(planCode);
                                if (planOpt.isPresent()) {
                                    planId = planOpt.get().getId();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Multi-item resolution failed for sub {}: {}", sub.getId(), e.getMessage());
        }

        logger.info("Upsert subscription: id={}, status={}, planId={}, customer={}, decodedUserId={}, creditQty={}",
                    subscriptionId, status, planId, sub.getCustomer(), decodedUserId, creditQuantity);

        if (planId != null) {
            subscriptionService.onSubscriptionUpsert(
                    eventId,
                    subscriptionId,
                    status,
                    planId,
                    stripePriceId,
                    startAt,
                    endAt,
                    decodedUserId,
                    nonce,
                    creditQuantity,
                    creditStripePriceId
                                                    );

            // Safety net: only allow ONE live sub per customer.
            if ("active".equals(status) || "trialing".equals(status)) {
                try {
                    int n = stripeBillingService.cancelOtherStripeSubscriptions(sub.getCustomer(), sub.getId(), false);
                    if (n > 0) {
                        logger.info("Enforce single-sub: {} old subs canceled for customer {}", n, sub.getCustomer());
                    }
                } catch (Exception e) {
                    logger.error("Enforce single-sub failed for customer={}, keepSub={}: {}",
                                 sub.getCustomer(), sub.getId(), e.getMessage(), e);
                }
            }

        } else {
            logger.warn("No plan_id found in subscription {}, cannot upsert with plan", subscriptionId);
        }

        // PR9: subscription upsert means the user's tier may have changed.
        // Fan-out gateway-cache invalidation to the user + all members of
        // every org they OWN, so the new activeOrgPlan is visible on the
        // next request instead of stale-up-to-5min.
        if (decodedUserId != null) {
            fanOutCacheBustForSubscriptionChange(decodedUserId, "subscription.upsert");
        }
    }

    /**
     * PR9 - fan-out cache invalidation after a subscription change. Delegates to the shared
     * {@link com.apimarketplace.auth.service.SubscriptionCacheBuster} so the Stripe-webhook path
     * and the admin comp-plan grant use the same entitlement-propagation pipe. No-op when the
     * buster bean is unwired (CE / slim test fixture).
     */
    private void fanOutCacheBustForSubscriptionChange(Long ownerUserId, String reason) {
        if (subscriptionCacheBuster != null) {
            subscriptionCacheBuster.fanOutForOwner(ownerUserId, reason);
        }
    }

    private String resolvePlanCodeFromPriceId(String priceId) {
        if (priceId == null || priceId.isBlank()) return null;

        // Use the price cache to resolve the planCode
        Map<String, String> cachedPrices = priceCacheService.getAllCachedPrices();
        if (cachedPrices == null || cachedPrices.isEmpty()) {
            logger.warn("Price cache empty, unable to resolve planCode for priceId: {}", priceId);
            return null;
        }

        // Search in cache: format "PLANCODE_CADENCE" -> "price_id"
        for (var entry : cachedPrices.entrySet()) {
            if (priceId.equals(entry.getValue())) {
                String key = entry.getKey();
                // Extract the planCode (part before the "_")
                int underscoreIndex = key.lastIndexOf('_');
                if (underscoreIndex > 0) {
                    String planCode = key.substring(0, underscoreIndex);
                    logger.debug("PlanCode resolved: {} -> {}", priceId, planCode);
                    return planCode;
                }
            }
        }

        logger.warn("PlanCode not found for priceId: {} in price cache", priceId);
        return null;
    }

    private void handleSubscriptionDeleted(Subscription sub, Event event) {
        logger.info("Subscription deleted: {}", sub.getId());
        // Resolve userId BEFORE cancellation in case the cascade tx detaches
        // the BillingCustomer row.
        Long userId = subscriptionRepository.findByProviderSubscriptionId(sub.getId())
                .map(s -> s.getBillingCustomer())
                .map(bc -> bc.getUser())
                .map(u -> u.getId())
                .orElse(null);
        subscriptionService.cancelSubscription(sub.getId());
        // PR9: TEAM → FREE transition. Without the fan-out, members keep
        // their TEAM-tier capability gates for up to 5min - silent
        // over-grant window. Closes the runbook §"Known gaps until PR9".
        if (userId != null) {
            fanOutCacheBustForSubscriptionChange(userId, "subscription.deleted");
        }
    }

    private void handleInvoicePaid(Invoice invoice) {
        String subId = getSubscriptionIdFromInvoice(invoice);
        String billingReason = invoice.getBillingReason();
        logger.info("Invoice paid: {} (subscription={}, billingReason={})", invoice.getId(), subId, billingReason);

        // Option A - credit-pack tier upgrade: grant credits when the dedicated
        // one-shot invoice is paid. Routed by metadata.kind ("credit_upgrade")
        // OR by presence of a PendingCreditUpgrade row keyed on invoice.id
        // (defensive against metadata stripped by intermediary tooling).
        if (handleCreditUpgradeInvoicePaidIfApplicable(invoice)) {
            return;
        }

        // Handle subscription renewal: reset + re-grant credits.
        if ("subscription_cycle".equals(billingReason) && subId != null) {
            try {
                var localSub = subscriptionRepository.findByProviderSubscriptionId(subId);
                if (localSub.isPresent()) {
                    com.apimarketplace.auth.domain.Subscription subscription = localSub.get();
                    Long userId = subscription.getBillingCustomer().getUser().getId();

                    creditAttributionService.attributeOnRenewal(userId, subscription);
                    logger.info("Credits renewed for userId={} on subscription cycle (invoice={})", userId, invoice.getId());

                    // PR9: renewal regrants credits - members of orgs the
                    // user owns should see fresh credit balance immediately,
                    // not after 5min TTL.
                    fanOutCacheBustForSubscriptionChange(userId, "invoice.paid.renewal");
                } else {
                    logger.warn("No local subscription found for providerSubscriptionId={} on invoice.paid", subId);
                }
            } catch (Exception e) {
                logger.error("Failed to attribute renewal credits for invoice {}: {}", invoice.getId(), e.getMessage(), e);
            }
        }

        // Referral: qualify the redeemer's pending conversion on any captured paid
        // invoice (subscription_create first payment, or the first positive cycle
        // after a trial). Only PENDING rows transition, so a replayed invoice.paid
        // (typed or RAW) is safe.
        tryQualifyReferralConversion(invoice, subId);
    }

    /**
     * Qualify a redeemer's PENDING referral on a captured paid invoice. Gated on
     * status=paid, amount_paid>0, and a non-FREE plan (the internal FREE sub never
     * raises Stripe invoices). When the local subscription is not yet provisioned,
     * the reward sweeper qualifies it on the next tick.
     */
    private void tryQualifyReferralConversion(Invoice invoice, String subId) {
        try {
            if (rewardService == null || invoice == null || subId == null) return;
            if (!"paid".equalsIgnoreCase(invoice.getStatus())) return;
            Long amountPaid = invoice.getAmountPaid();
            if (amountPaid == null || amountPaid <= 0) return;
            var localSub = subscriptionRepository.findByProviderSubscriptionId(subId).orElse(null);
            if (localSub == null) return; // sweeper backstop
            var plan = localSub.getPlan();
            if (plan != null && "FREE".equalsIgnoreCase(plan.getCode())) return;
            Long userId = localSub.getBillingCustomer().getUser().getId();
            rewardService.qualifyOnPaidConversion(userId, subId);
        } catch (Exception e) {
            logger.error("Referral qualify failed for invoice {}: {}",
                    invoice != null ? invoice.getId() : "?", e.getMessage(), e);
        }
    }

    /** Full refund of a converting charge claws back the referral reward. */
    private void handleChargeRefunded(com.stripe.model.Charge charge) {
        try {
            if (rewardService == null || charge == null) return;
            Long amount = charge.getAmount();
            Long refunded = charge.getAmountRefunded();
            if (amount == null || amount <= 0 || refunded == null || refunded < amount) {
                logger.info("Charge {} not fully refunded (amount={}, refunded={}); no clawback",
                        charge.getId(), amount, refunded);
                return;
            }
            clawbackByCustomer(charge.getCustomer(), "REFUNDED");
        } catch (Exception e) {
            logger.error("Error in handleChargeRefunded: {}", e.getMessage(), e);
        }
    }

    /** A chargeback (dispute) on a converting charge claws back the referral reward. */
    private void handleDisputeCreated(com.stripe.model.Dispute dispute) {
        try {
            if (rewardService == null || dispute == null) return;
            clawbackByDisputedCharge(dispute.getCharge(), "DISPUTED");
        } catch (Exception e) {
            logger.error("Error in handleDisputeCreated: {}", e.getMessage(), e);
        }
    }

    private void handleChargeRefundedRaw(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw == null) return;
            var json = objectMapper.readTree(raw.toJson());
            String chargeId = json.path("id").asText(null);
            if (chargeId == null || chargeId.isBlank()) return;
            handleChargeRefunded(stripeClient.charges().retrieve(chargeId));
        } catch (Exception e) {
            logger.error("Error in handleChargeRefundedRaw: {}", e.getMessage(), e);
        }
    }

    private void handleDisputeCreatedRaw(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw == null) return;
            var json = objectMapper.readTree(raw.toJson());
            clawbackByDisputedCharge(json.path("charge").asText(null), "DISPUTED");
        } catch (Exception e) {
            logger.error("Error in handleDisputeCreatedRaw: {}", e.getMessage(), e);
        }
    }

    private void clawbackByDisputedCharge(String chargeId, String reason) {
        if (rewardService == null || chargeId == null || chargeId.isBlank()) return;
        try {
            com.stripe.model.Charge charge = stripeClient.charges().retrieve(chargeId);
            clawbackByCustomer(charge.getCustomer(), reason);
        } catch (Exception e) {
            logger.error("Dispute clawback resolve failed for charge {}: {}", chargeId, e.getMessage(), e);
        }
    }

    /** Resolve the referee from the Stripe customer and claw back their referral reward. */
    private void clawbackByCustomer(String customerId, String reason) {
        if (rewardService == null || customerId == null || customerId.isBlank()) return;
        billingCustomerRepository.findByProviderCustomerId(customerId).ifPresent(bc ->
                rewardService.clawbackByRedeemerUserId(bc.getUser().getId(), reason));
    }

    /**
     * Detect that this {@code invoice.paid} event corresponds to an Option A
     * credit-tier upgrade and route the grant accordingly. Returns true when
     * the event was handled here (caller should stop further processing -
     * notably the renewal grant must NOT fire for these one-shot invoices).
     */
    private boolean handleCreditUpgradeInvoicePaidIfApplicable(Invoice invoice) {
        // First: try metadata routing (cheapest).
        boolean isCreditUpgrade = invoice.getMetadata() != null
                && "credit_upgrade".equals(invoice.getMetadata().get("kind"));

        // Defensive: even if metadata is missing, a PendingCreditUpgrade row
        // keyed on this invoice id is authoritative.
        Optional<PendingCreditUpgrade> pendingOpt = pendingCreditUpgradeRepository
                .findByStripeInvoiceId(invoice.getId());

        if (!isCreditUpgrade && pendingOpt.isEmpty()) {
            return false;
        }

        PendingCreditUpgrade pending;
        if (pendingOpt.isEmpty()) {
            // Metadata says credit_upgrade but no pending row - possible if the
            // synchronous flow's outer @Transactional rolled back after the Stripe
            // invoice was created. Reconstruct the pending row from metadata so the
            // grant can still happen (the user already paid).
            logger.warn("invoice.paid kind=credit_upgrade but no PendingCreditUpgrade row for invoice={} - " +
                    "reconstructing from metadata", invoice.getId());
            pending = reconstructPendingFromMetadata(invoice);
            if (pending == null) {
                logger.error("Cannot reconstruct PendingCreditUpgrade from invoice {} metadata - " +
                        "GRANT SKIPPED, manual review required", invoice.getId());
                return true; // claim event so renewal path doesn't double-fire on this credit_upgrade invoice
            }
            try {
                pendingCreditUpgradeRepository.save(pending);
            } catch (Exception e) {
                logger.warn("Could not persist reconstructed PendingCreditUpgrade for invoice {}: {}",
                        invoice.getId(), e.getMessage());
                // Continue with in-memory pending - grant will still happen idempotently.
            }
        } else {
            pending = pendingOpt.get();
        }

        // If the upgrade was paused on 3DS, the synchronous flow returned BEFORE
        // calling subscriptions.update - so the recurring sub-item still points
        // at the OLD tier price. Now that payment has cleared, align it before
        // granting credits, otherwise the user gets one cycle of free credits
        // followed by a wrong-tier renewal.
        boolean needsSubItemAlignment = PendingCreditUpgrade.STATUS_PENDING_3DS.equals(pending.getStatus())
                || PendingCreditUpgrade.STATUS_PAID_SUB_PENDING.equals(pending.getStatus());
        if (needsSubItemAlignment) {
            try {
                String invoicePm = invoice.getDefaultPaymentMethod();
                if (invoicePm == null && invoice.getCustomer() != null) {
                    invoicePm = stripeBillingService.findAnyAttachedPaymentMethod(
                            invoice.getCustomer());
                }
                alignSubItemForUpgrade(pending, invoicePm);
            } catch (Exception e) {
                // Log + continue: the grant must still happen for the paid invoice.
                // Reconciliation job will retry the sub-item update.
                logger.error("Failed to align sub-item after invoice.paid for upgrade (invoice={}, sub={}): {}",
                        invoice.getId(), pending.getProviderSubscriptionId(), e.getMessage(), e);
            }
        }

        try {
            creditAttributionService.handleCreditUpgradeInvoicePaid(pending);
            // Mark COMPLETED if not already (sync flow may have already set it).
            if (!PendingCreditUpgrade.STATUS_COMPLETED.equals(pending.getStatus())
                    && !PendingCreditUpgrade.STATUS_FAILED.equals(pending.getStatus())) {
                pending.setStatus(PendingCreditUpgrade.STATUS_COMPLETED);
                pending.setCompletedAt(java.time.LocalDateTime.now());
                pendingCreditUpgradeRepository.save(pending);
            }
            logger.info("Credit-upgrade grant routed via invoice.paid: invoice={}, user={}, tier={}",
                    invoice.getId(), pending.getUserId(), pending.getTargetTierIndex());
        } catch (Exception e) {
            logger.error("Failed to grant credit-upgrade for invoice {}: {}",
                    invoice.getId(), e.getMessage(), e);
            // Don't rethrow: webhook will retry; idempotence on ledger source_id protects us.
        }
        return true;
    }

    /**
     * Recreate a {@link PendingCreditUpgrade} from invoice metadata when the row
     * is missing in DB (typical after a synchronous flow rollback). All fields
     * required by downstream handlers are present in {@code invoice.metadata}
     * because {@link com.apimarketplace.auth.service.StripeBillingService#upgradeCreditTierImmediate}
     * stamps them at invoice creation time.
     *
     * <p>Returns null if any required metadata field is missing or malformed.
     */
    private PendingCreditUpgrade reconstructPendingFromMetadata(Invoice invoice) {
        var md = invoice.getMetadata();
        if (md == null) return null;
        try {
            String userIdStr = md.get("userId");
            String subscriptionIdStr = md.get("subscriptionId");
            String providerSubId = md.get("providerSubscriptionId");
            String targetTierStr = md.get("targetTierIndex");
            String targetQtyStr = md.get("targetCreditQuantity");
            String targetCreditPriceId = md.get("targetCreditPriceId");
            if (userIdStr == null || subscriptionIdStr == null || providerSubId == null
                    || targetTierStr == null || targetQtyStr == null || targetCreditPriceId == null) {
                return null;
            }
            PendingCreditUpgrade p = new PendingCreditUpgrade();
            p.setUserId(Long.parseLong(userIdStr));
            p.setSubscriptionId(Long.parseLong(subscriptionIdStr));
            p.setProviderSubscriptionId(providerSubId);
            p.setStripeInvoiceId(invoice.getId());
            // Item id is unknown when reconstructing from metadata; a placeholder
            // is required to satisfy @NotBlank validation on save. The reconciler
            // path that needs the real id (3DS abandon → delete item) skips rows
            // whose item id starts with "metadata-fallback:" (see reconciler).
            p.setStripeInvoiceItemId("metadata-fallback:" + invoice.getId());
            p.setTargetTierIndex(Integer.parseInt(targetTierStr));
            p.setTargetCreditQuantity(Integer.parseInt(targetQtyStr));
            p.setTargetCreditPriceId(targetCreditPriceId);
            p.setStatus(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING);
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Update the recurring credit sub-item on the Stripe subscription to the
     * target tier price/quantity. Idempotent via a stable Idempotency-Key
     * derived from the pending row (same as the synchronous flow uses).
     *
     * <p>Called when {@code invoice.paid} arrives for a pending upgrade whose
     * synchronous flow stopped before {@code subscriptions.update()} (3DS
     * branch, or sync StripeException post-pay). Without this, the next
     * renewal would bill the old tier.
     */
    private void alignSubItemForUpgrade(PendingCreditUpgrade pending, String paymentMethodId) throws StripeException {
        com.stripe.model.Subscription stripeSub = stripeClient.subscriptions()
                .retrieve(pending.getProviderSubscriptionId());

        String creditItemId = null;
        for (var item : stripeSub.getItems().getData()) {
            if (priceCacheService.isCreditPackPrice(item.getPrice().getId())) {
                creditItemId = item.getId();
                break;
            }
        }

        var itemBuilder = SubscriptionUpdateParams.Item.builder()
                .setPrice(pending.getTargetCreditPriceId())
                .setQuantity((long) pending.getTargetCreditQuantity());
        if (creditItemId != null) {
            itemBuilder.setId(creditItemId);
        }

        var subUpdateBuilder = SubscriptionUpdateParams.builder()
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                .addItem(itemBuilder.build());
        if (paymentMethodId != null && !paymentMethodId.isBlank()) {
            subUpdateBuilder.setDefaultPaymentMethod(paymentMethodId);
            logger.info("Pinning PM {} on subscription {} after hosted-invoice payment",
                    paymentMethodId, pending.getProviderSubscriptionId());
        }
        SubscriptionUpdateParams params = subUpdateBuilder.build();

        String idemKey = "upgrade-align:" + pending.getStripeInvoiceId() + ":sub";
        RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idemKey).build();
        stripeClient.subscriptions().update(pending.getProviderSubscriptionId(), params, opts);

        // Sync the local creditQuantity so the subsequent customer.subscription.updated
        // webhook does NOT trigger handleCreditPackChange (old == new).
        subscriptionRepository.findById(pending.getSubscriptionId()).ifPresent(sub -> {
            sub.setCreditQuantity(pending.getTargetCreditQuantity());
            sub.setUpdatedAt(java.time.LocalDateTime.now());
            subscriptionRepository.save(sub);
        });

        logger.info("Aligned sub-item post-3DS for upgrade invoice={}, sub={}, qty={}",
                pending.getStripeInvoiceId(), pending.getProviderSubscriptionId(),
                pending.getTargetCreditQuantity());
    }

    /**
     * Handle {@code invoice.payment_failed} for an Option A credit-tier upgrade.
     * Marks the pending row FAILED so the frontend / reconcile job know to stop
     * waiting. No ledger grant is performed.
     */
    private void handleInvoicePaymentFailed(Invoice invoice) {
        logger.info("Invoice payment_failed: {} (status={})", invoice.getId(), invoice.getStatus());

        boolean isCreditUpgrade = invoice.getMetadata() != null
                && "credit_upgrade".equals(invoice.getMetadata().get("kind"));
        Optional<PendingCreditUpgrade> pendingOpt = pendingCreditUpgradeRepository
                .findByStripeInvoiceId(invoice.getId());

        if (!isCreditUpgrade && pendingOpt.isEmpty()) {
            // Not one of ours - ignore (could be a renewal failure; Stripe dunning handles it).
            return;
        }
        if (pendingOpt.isEmpty()) {
            logger.warn("invoice.payment_failed kind=credit_upgrade but no PendingCreditUpgrade row for invoice={}",
                    invoice.getId());
            return;
        }
        PendingCreditUpgrade pending = pendingOpt.get();
        if (pending.isTerminal()) {
            logger.info("Pending credit upgrade already terminal (status={}) for invoice={} - no-op",
                    pending.getStatus(), invoice.getId());
            return;
        }
        pending.setStatus(PendingCreditUpgrade.STATUS_FAILED);
        pending.setErrorMessage("invoice.payment_failed");
        pendingCreditUpgradeRepository.save(pending);
        logger.warn("Credit upgrade marked FAILED: invoice={}, user={}, tier={}",
                invoice.getId(), pending.getUserId(), pending.getTargetTierIndex());
    }

    private void handleInvoicePaymentSucceeded(Invoice invoice) {
        String subscriptionId = getSubscriptionIdFromInvoice(invoice);
        logger.info("Invoice payment succeeded: {} (subscription={}, billingReason={})",
                invoice.getId(), subscriptionId, invoice.getBillingReason());
        // Provisioning is handled by customer.subscription.created/updated webhooks.
        // No handleSubscriptionUpsert call here to avoid duplicate credit grants.
    }

    /**
     * Helper to extract the subscription ID from an Invoice.
     * SDK 31+: uses the new parent.subscription_details API.
     * Fallback: query Stripe subscriptions by customer if parent API fails.
     */
    private String getSubscriptionIdFromInvoice(Invoice invoice) {
        if (invoice == null) return null;

        // 1) SDK 31+ new API: parent.subscription_details.subscription
        try {
            if (invoice.getParent() != null &&
                "subscription_details".equals(invoice.getParent().getType()) &&
                invoice.getParent().getSubscriptionDetails() != null) {
                String subId = invoice.getParent().getSubscriptionDetails().getSubscription();
                if (subId != null && !subId.isBlank()) {
                    return subId;
                }
            }
        } catch (NoSuchMethodError | Exception e) {
            logger.debug("Fallback: getParent() not available for invoice {}", invoice.getId());
        }

        // 2) Fallback: find subscription by customer ID
        try {
            String customerId = invoice.getCustomer();
            if (customerId != null && !customerId.isBlank()) {
                var params = SubscriptionListParams.builder()
                        .setCustomer(customerId)
                        .setLimit(1L)
                        .build();
                var list = stripeClient.subscriptions().list(params);
                if (list != null && !list.getData().isEmpty()) {
                    String subId = list.getData().get(0).getId();
                    logger.info("Resolved subscription {} from customer {} fallback for invoice {}",
                            subId, customerId, invoice.getId());
                    return subId;
                }
            }
        } catch (Exception e) {
            logger.warn("Fallback subscription lookup failed for invoice {}: {}",
                    invoice.getId(), e.getMessage());
        }

        logger.warn("Could not extract subscription ID from invoice {}", invoice.getId());
        return null;
    }

    private void handleCustomerDeleted(Customer stripeCustomer) {
        try {
            String customerId = stripeCustomer.getId();
            logger.info("Stripe customer deleted: {}", customerId);

            billingCustomerRepository.findByProviderCustomerId(customerId).ifPresentOrElse(bc -> {
                bc.setProviderCustomerId(null);
                billingCustomerRepository.save(bc);

                var subs = subscriptionRepository.findByBillingCustomer_IdOrderByCreatedAtDesc(bc.getId());
                var now = LocalDateTime.now();
                boolean changed = false;
                for (var s : subs) {
                    if ("active".equals(s.getStatus()) ||
                        "trialing".equals(s.getStatus()) ||
                        "past_due".equals(s.getStatus()) ||
                        "incomplete".equals(s.getStatus())) {
                        s.setStatus("canceled");
                        s.setCancelAtPeriodEnd(true);
                        s.setUpdatedAt(now);
                        changed = true;
                        logger.info("Local subscription {} canceled due to customer deletion {}", s.getId(), customerId);
                    }
                }
                if (changed) subscriptionRepository.saveAll(subs);

                User user = bc.getUser();
                logger.info("Customer deletion processed for user {} (plan→FREE handled via subscription update)", user.getId());

                // V311: customer deletion drops the owner to the FREE workspace cap (no active sub
                // left) WITHOUT flowing through onSubscriptionUpsert - pause any over-cap workspaces.
                subscriptionService.reconcileWorkspacesAfterPlanLoss(user.getId());
            }, () -> logger.warn("No BillingCustomer found for deleted Stripe customer {}", customerId));

        } catch (Exception e) {
            logger.error("Error in handleCustomerDeleted: {}", e.getMessage(), e);
        }
    }

    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var r = request.getReader()) {
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ======================= SUBSCRIPTION SCHEDULE HANDLERS =======================

    /**
     * Handle subscription_schedule.created event
     * Logged for audit purposes
     */
    private void handleSubscriptionScheduleCreated(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                String scheduleId = json.path("id").asText(null);
                String subscriptionId = json.path("subscription").asText(null);
                String status = json.path("status").asText(null);
                
                logger.info("Subscription schedule created: scheduleId={}, subscriptionId={}, status={}",
                        scheduleId, subscriptionId, status);
            }
        } catch (Exception e) {
            logger.error("Error handling subscription_schedule.created: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle subscription_schedule.updated event
     * Logged for audit purposes, phase changes tracked
     */
    private void handleSubscriptionScheduleUpdated(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                String scheduleId = json.path("id").asText(null);
                String status = json.path("status").asText(null);
                var phases = json.path("phases");
                int phaseCount = phases.isArray() ? phases.size() : 0;
                
                logger.info("Subscription schedule updated: scheduleId={}, status={}, phases={}",
                        scheduleId, status, phaseCount);
            }
        } catch (Exception e) {
            logger.error("Error handling subscription_schedule.updated: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle subscription_schedule.released event
     * The schedule has been released and the subscription is now active with the new plan
     */
    private void handleSubscriptionScheduleReleased(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                String scheduleId = json.path("id").asText(null);
                String subscriptionId = json.path("released_subscription").asText(null);
                
                logger.info("Subscription schedule released: scheduleId={}, releasedSubscription={}",
                        scheduleId, subscriptionId);
                
                // The Stripe subscription will be updated and trigger customer.subscription.updated
                // No action needed here, the existing handler will take care of it
            }
        } catch (Exception e) {
            logger.error("Error handling subscription_schedule.released: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle subscription_schedule.completed event
     * The schedule has completed all phases
     */
    private void handleSubscriptionScheduleCompleted(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                String scheduleId = json.path("id").asText(null);
                
                logger.info("Subscription schedule completed: scheduleId={}", scheduleId);
                
                // The schedule is complete, the subscription has been updated
                // Plan/quota changes will be handled by customer.subscription.updated
            }
        } catch (Exception e) {
            logger.error("Error handling subscription_schedule.completed: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle subscription_schedule.canceled event
     * The schedule has been canceled (user changed their mind)
     */
    private void handleSubscriptionScheduleCanceled(Event event) {
        try {
            var raw = event.getData().getObject();
            if (raw != null) {
                var json = objectMapper.readTree(raw.toJson());
                String scheduleId = json.path("id").asText(null);
                String subscriptionId = json.path("subscription").asText(null);
                
                logger.info("Subscription schedule canceled: scheduleId={}, subscriptionId={}",
                        scheduleId, subscriptionId);
                
                // The schedule was canceled, the subscription remains unchanged
                // Nothing to do locally, the subscription keeps its current plan
            }
        } catch (Exception e) {
            logger.error("Error handling subscription_schedule.canceled: {}", e.getMessage(), e);
        }
    }
}

