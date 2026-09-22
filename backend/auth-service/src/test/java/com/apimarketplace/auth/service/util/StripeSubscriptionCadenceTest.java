package com.apimarketplace.auth.service.util;

import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link StripeSubscriptionPeriod#cadenceOf}: the one derivation of a billing cadence from a
 * Stripe subscription, shared by the cycle-change path and the webhook upsert. Since V498 a
 * {@code yearly} label decides whether a customer is granted their pack monthly, so a wrong
 * answer here is a customer paid for twelve months and served one.
 */
@DisplayName("StripeSubscriptionPeriod.cadenceOf")
class StripeSubscriptionCadenceTest {

    private static SubscriptionItem item(String priceId, String interval) {
        SubscriptionItem item = mock(SubscriptionItem.class);
        Price price = mock(Price.class);
        lenient().when(price.getId()).thenReturn(priceId);
        if (interval != null) {
            Price.Recurring recurring = mock(Price.Recurring.class);
            lenient().when(recurring.getInterval()).thenReturn(interval);
            lenient().when(price.getRecurring()).thenReturn(recurring);
        }
        lenient().when(item.getPrice()).thenReturn(price);
        return item;
    }

    private static Subscription subscription(SubscriptionItem... items) {
        Subscription sub = mock(Subscription.class);
        SubscriptionItemCollection collection = mock(SubscriptionItemCollection.class);
        lenient().when(collection.getData()).thenReturn(List.of(items));
        lenient().when(sub.getItems()).thenReturn(collection);
        return sub;
    }

    @Test
    @DisplayName("a year interval is yearly, a month interval is monthly")
    void mapsTheInterval() {
        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(item("price_base", "year")), id -> false))
                .isEqualTo("yearly");
        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(item("price_base", "month")), id -> false))
                .isEqualTo("monthly");
    }

    @Test
    @DisplayName("the credit-pack item is skipped: the BASE item speaks for the plan")
    void skipsTheCreditPackItem() {
        Subscription sub = subscription(item("price_pack", "month"), item("price_base", "year"));

        assertThat(StripeSubscriptionPeriod.cadenceOf(sub, "price_pack"::equals)).isEqualTo("yearly");
    }

    @Test
    @DisplayName("unknown when there is nothing to read: no items, no expanded price, no recurring block")
    void nullWhenUnknown() {
        assertThat(StripeSubscriptionPeriod.cadenceOf(null, id -> false)).isNull();
        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(), id -> false)).isNull();
        SubscriptionItem noPrice = mock(SubscriptionItem.class);
        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(noPrice), id -> false)).isNull();
        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(item("price_base", null)), id -> false)).isNull();
    }

    @Test
    @DisplayName("a recurring block without an interval reads as monthly, exactly as the cycle-change path always did")
    void recurringWithoutIntervalIsMonthly() {
        SubscriptionItem item = mock(SubscriptionItem.class);
        Price price = mock(Price.class);
        Price.Recurring recurring = mock(Price.Recurring.class);
        lenient().when(recurring.getInterval()).thenReturn(null);
        lenient().when(price.getRecurring()).thenReturn(recurring);
        lenient().when(price.getId()).thenReturn("price_base");
        lenient().when(item.getPrice()).thenReturn(price);

        assertThat(StripeSubscriptionPeriod.cadenceOf(subscription(item), id -> false)).isEqualTo("monthly");
    }

    @Test
    @DisplayName("a caller that cannot tell the pack apart still gets the right answer, because both items share the interval")
    void packSharesTheBaseInterval() {
        Subscription sub = subscription(item("price_pack", "year"), item("price_base", "year"));

        assertThat(StripeSubscriptionPeriod.cadenceOf(sub, id -> false)).isEqualTo("yearly");
    }
}
