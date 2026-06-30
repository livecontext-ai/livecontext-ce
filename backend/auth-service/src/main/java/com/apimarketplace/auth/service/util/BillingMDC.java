package com.apimarketplace.auth.service.util;

import org.slf4j.MDC;
import org.slf4j.Logger;

/**
 * Utility class for structured logging in billing operations.
 * 
 * Uses SLF4J MDC (Mapped Diagnostic Context) to add contextual information
 * to all log messages within a billing operation scope.
 * 
 * This enables:
 * - Request tracing across services
 * - Filtering logs by userId, subscriptionId, etc.
 * - Correlation with Stripe Dashboard events
 * 
 * Usage:
 * <pre>
 * try (var ctx = BillingMDC.context(userId, subscriptionId, "upgrade")) {
 *     // All logs within this block will include userId, subscriptionId, action
 *     log.info("Processing plan change");
 * }
 * // MDC is automatically cleared after the block
 * </pre>
 */
public final class BillingMDC implements AutoCloseable {

    public static final String USER_ID = "billing.userId";
    public static final String SUBSCRIPTION_ID = "billing.subscriptionId";
    public static final String ACTION = "billing.action";
    public static final String PLAN_CODE = "billing.planCode";
    public static final String STRIPE_EVENT_ID = "billing.stripeEventId";
    public static final String STRIPE_CUSTOMER_ID = "billing.stripeCustomerId";

    private BillingMDC() {
        // Private constructor - use static factory methods
    }

    /**
     * Creates a new MDC context for billing operations.
     * 
     * @param userId User ID
     * @param subscriptionId Stripe subscription ID (can be null)
     * @param action Action being performed (e.g., "upgrade", "downgrade", "cancel")
     * @return AutoCloseable context that clears MDC when closed
     */
    public static BillingMDC context(Long userId, String subscriptionId, String action) {
        if (userId != null) {
            MDC.put(USER_ID, userId.toString());
        }
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            MDC.put(SUBSCRIPTION_ID, subscriptionId);
        }
        if (action != null && !action.isBlank()) {
            MDC.put(ACTION, action);
        }
        return new BillingMDC();
    }

    /**
     * Creates a new MDC context for webhook processing.
     * 
     * @param eventId Stripe event ID
     * @param eventType Stripe event type
     * @return AutoCloseable context that clears MDC when closed
     */
    public static BillingMDC webhookContext(String eventId, String eventType) {
        if (eventId != null) {
            MDC.put(STRIPE_EVENT_ID, eventId);
        }
        if (eventType != null) {
            MDC.put(ACTION, "webhook:" + eventType);
        }
        return new BillingMDC();
    }

    /**
     * Adds additional context to the current MDC.
     */
    public BillingMDC withPlanCode(String planCode) {
        if (planCode != null) {
            MDC.put(PLAN_CODE, planCode);
        }
        return this;
    }

    /**
     * Adds Stripe customer ID to the current MDC.
     */
    public BillingMDC withCustomerId(String customerId) {
        if (customerId != null) {
            MDC.put(STRIPE_CUSTOMER_ID, customerId);
        }
        return this;
    }

    /**
     * Logs a billing operation start message with context.
     */
    public static void logStart(Logger logger, String message, Object... args) {
        logger.info("[BILLING-START] " + message, args);
    }

    /**
     * Logs a billing operation success message with context.
     */
    public static void logSuccess(Logger logger, String message, Object... args) {
        logger.info("[BILLING-OK] " + message, args);
    }

    /**
     * Logs a billing operation failure message with context.
     */
    public static void logFailure(Logger logger, String message, Object... args) {
        logger.error("[BILLING-FAIL] " + message, args);
    }

    /**
     * Clears all billing-related MDC entries.
     */
    @Override
    public void close() {
        MDC.remove(USER_ID);
        MDC.remove(SUBSCRIPTION_ID);
        MDC.remove(ACTION);
        MDC.remove(PLAN_CODE);
        MDC.remove(STRIPE_EVENT_ID);
        MDC.remove(STRIPE_CUSTOMER_ID);
    }

    /**
     * Clears all billing-related MDC entries (static version).
     */
    public static void clear() {
        MDC.remove(USER_ID);
        MDC.remove(SUBSCRIPTION_ID);
        MDC.remove(ACTION);
        MDC.remove(PLAN_CODE);
        MDC.remove(STRIPE_EVENT_ID);
        MDC.remove(STRIPE_CUSTOMER_ID);
    }
}
