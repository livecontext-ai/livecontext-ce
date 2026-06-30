package com.apimarketplace.auth.service.util;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StripeSubscriptionPeriod Tests")
class StripeSubscriptionPeriodTest {

    @Nested
    @DisplayName("getCurrentPeriod()")
    class GetCurrentPeriodTests {

        @Test
        @DisplayName("should return empty period for null subscription")
        void shouldReturnEmptyPeriodForNull() {
            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(null);

            assertThat(period.start()).isNull();
            assertThat(period.end()).isNull();
        }

        @Test
        @DisplayName("should extract period from subscription items (SDK 31+)")
        void shouldExtractPeriodFromItems() {
            Subscription sub = mock(Subscription.class);
            SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
            SubscriptionItem item = mock(SubscriptionItem.class);

            long startEpoch = 1700000000L;
            long endEpoch = 1702592000L;

            when(sub.getItems()).thenReturn(items);
            when(items.getData()).thenReturn(List.of(item));
            when(item.getCurrentPeriodStart()).thenReturn(startEpoch);
            when(item.getCurrentPeriodEnd()).thenReturn(endEpoch);

            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(sub);

            assertThat(period.start()).isNotNull();
            assertThat(period.end()).isNotNull();
        }

        @Test
        @DisplayName("should handle subscription with null items")
        void shouldHandleNullItems() {
            Subscription sub = mock(Subscription.class);
            when(sub.getItems()).thenReturn(null);

            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(sub);

            // Falls through to legacy path; result depends on legacy methods
            // At minimum should not throw
            assertThat(period).isNotNull();
        }

        @Test
        @DisplayName("should handle subscription with empty items data")
        void shouldHandleEmptyItemsData() {
            Subscription sub = mock(Subscription.class);
            SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);

            when(sub.getItems()).thenReturn(items);
            when(items.getData()).thenReturn(List.of());

            StripeSubscriptionPeriod.Period period = StripeSubscriptionPeriod.getCurrentPeriod(sub);

            assertThat(period).isNotNull();
        }
    }

    @Nested
    @DisplayName("getEndDate()")
    class GetEndDateTests {

        @Test
        @DisplayName("should return null for null subscription")
        void shouldReturnNullForNullSubscription() {
            LocalDateTime result = StripeSubscriptionPeriod.getEndDate(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return end date from period")
        void shouldReturnEndDateFromPeriod() {
            Subscription sub = mock(Subscription.class);
            SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
            SubscriptionItem item = mock(SubscriptionItem.class);

            long endEpoch = 1702592000L;
            when(sub.getItems()).thenReturn(items);
            when(items.getData()).thenReturn(List.of(item));
            when(item.getCurrentPeriodStart()).thenReturn(1700000000L);
            when(item.getCurrentPeriodEnd()).thenReturn(endEpoch);

            LocalDateTime result = StripeSubscriptionPeriod.getEndDate(sub);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getEndEpoch()")
    class GetEndEpochTests {

        @Test
        @DisplayName("should return null for null subscription")
        void shouldReturnNullForNullSubscription() {
            Long result = StripeSubscriptionPeriod.getEndEpoch(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return epoch seconds for end date")
        void shouldReturnEpochSeconds() {
            Subscription sub = mock(Subscription.class);
            SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
            SubscriptionItem item = mock(SubscriptionItem.class);

            long endEpoch = 1702592000L;
            when(sub.getItems()).thenReturn(items);
            when(items.getData()).thenReturn(List.of(item));
            when(item.getCurrentPeriodStart()).thenReturn(1700000000L);
            when(item.getCurrentPeriodEnd()).thenReturn(endEpoch);

            Long result = StripeSubscriptionPeriod.getEndEpoch(sub);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Period record")
    class PeriodTests {

        @Test
        @DisplayName("should return end epoch when end is present")
        void shouldReturnEndEpochWhenPresent() {
            LocalDateTime end = LocalDateTime.of(2025, 1, 15, 12, 0);
            StripeSubscriptionPeriod.Period period = new StripeSubscriptionPeriod.Period(null, end);

            Long result = period.endEpochOrDefault(30);

            long expectedEpoch = end.atZone(ZoneId.systemDefault()).toEpochSecond();
            assertThat(result).isEqualTo(expectedEpoch);
        }

        @Test
        @DisplayName("should return fallback when end is null")
        void shouldReturnFallbackWhenEndIsNull() {
            StripeSubscriptionPeriod.Period period = new StripeSubscriptionPeriod.Period(null, null);

            Long result = period.endEpochOrDefault(30);

            long approximateExpected = System.currentTimeMillis() / 1000 + 30 * 24 * 3600;
            assertThat(result).isBetween(approximateExpected - 10, approximateExpected + 10);
        }

        @Test
        @DisplayName("should store start and end correctly")
        void shouldStoreStartAndEnd() {
            LocalDateTime start = LocalDateTime.of(2025, 1, 1, 0, 0);
            LocalDateTime end = LocalDateTime.of(2025, 2, 1, 0, 0);

            StripeSubscriptionPeriod.Period period = new StripeSubscriptionPeriod.Period(start, end);

            assertThat(period.start()).isEqualTo(start);
            assertThat(period.end()).isEqualTo(end);
        }
    }
}
