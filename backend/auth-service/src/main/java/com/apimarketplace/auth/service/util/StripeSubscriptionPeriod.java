package com.apimarketplace.auth.service.util;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Utility class for extracting billing period data from a Stripe Subscription.
 *
 * <p>Pinned to Stripe SDK 31+ (API 2025-03-31) in pom.xml: currentPeriodStart/End
 * live on SubscriptionItem (not the Subscription itself, as in earlier SDKs).
 */
public final class StripeSubscriptionPeriod {

    private StripeSubscriptionPeriod() {}

    /**
     * Gets the current billing period from a Stripe Subscription.
     *
     * @param sub The Stripe subscription
     * @return A Period record with start and end dates (may be null when the
     *         subscription has no items, e.g. pending/incomplete state)
     */
    public static Period getCurrentPeriod(Subscription sub) {
        if (sub == null || sub.getItems() == null
                || sub.getItems().getData() == null
                || sub.getItems().getData().isEmpty()) {
            return new Period(null, null);
        }
        SubscriptionItem item = sub.getItems().getData().get(0);
        Long start = item.getCurrentPeriodStart();
        Long end = item.getCurrentPeriodEnd();
        return new Period(toLdt(start), toLdt(end));
    }

    /**
     * Convenience method to get only the period end date.
     * 
     * @param sub The Stripe subscription
     * @return The period end date, or null
     */
    public static LocalDateTime getEndDate(Subscription sub) {
        return getCurrentPeriod(sub).end();
    }

    /**
     * Convenience method to get the period end as epoch seconds.
     * 
     * @param sub The Stripe subscription
     * @return The period end in epoch seconds, or null
     */
    public static Long getEndEpoch(Subscription sub) {
        Period period = getCurrentPeriod(sub);
        if (period.end() == null) {
            return null;
        }
        return period.end().atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static LocalDateTime toLdt(Long epochSeconds) {
        return (epochSeconds == null) ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    /**
     * Record containing billing period dates.
     * 
     * @param start Period start date
     * @param end Period end date
     */
    public record Period(LocalDateTime start, LocalDateTime end) {
        
        /**
         * Returns the end date as epoch seconds, or a fallback value.
         * 
         * @param fallbackDays Number of days to add to current time as fallback
         * @return Epoch seconds for end date, or fallback if null
         */
        public Long endEpochOrDefault(long fallbackDays) {
            if (end != null) {
                return end.atZone(ZoneId.systemDefault()).toEpochSecond();
            }
            return System.currentTimeMillis() / 1000 + fallbackDays * 24 * 3600;
        }
    }
}
