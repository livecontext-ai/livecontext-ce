package com.apimarketplace.auth.service.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BillingMDC Tests")
class BillingMDCTest {

    @AfterEach
    void tearDown() {
        BillingMDC.clear();
    }

    @Nested
    @DisplayName("context()")
    class ContextTests {

        @Test
        @DisplayName("should set userId in MDC")
        void shouldSetUserIdInMdc() {
            try (var ctx = BillingMDC.context(42L, "sub_123", "upgrade")) {
                assertThat(MDC.get(BillingMDC.USER_ID)).isEqualTo("42");
            }
        }

        @Test
        @DisplayName("should set subscriptionId in MDC")
        void shouldSetSubscriptionIdInMdc() {
            try (var ctx = BillingMDC.context(1L, "sub_abc", "upgrade")) {
                assertThat(MDC.get(BillingMDC.SUBSCRIPTION_ID)).isEqualTo("sub_abc");
            }
        }

        @Test
        @DisplayName("should set action in MDC")
        void shouldSetActionInMdc() {
            try (var ctx = BillingMDC.context(1L, "sub_abc", "downgrade")) {
                assertThat(MDC.get(BillingMDC.ACTION)).isEqualTo("downgrade");
            }
        }

        @Test
        @DisplayName("should not set userId if null")
        void shouldNotSetUserIdIfNull() {
            try (var ctx = BillingMDC.context(null, "sub_abc", "upgrade")) {
                assertThat(MDC.get(BillingMDC.USER_ID)).isNull();
            }
        }

        @Test
        @DisplayName("should not set subscriptionId if null")
        void shouldNotSetSubscriptionIdIfNull() {
            try (var ctx = BillingMDC.context(1L, null, "upgrade")) {
                assertThat(MDC.get(BillingMDC.SUBSCRIPTION_ID)).isNull();
            }
        }

        @Test
        @DisplayName("should not set subscriptionId if blank")
        void shouldNotSetSubscriptionIdIfBlank() {
            try (var ctx = BillingMDC.context(1L, "   ", "upgrade")) {
                assertThat(MDC.get(BillingMDC.SUBSCRIPTION_ID)).isNull();
            }
        }

        @Test
        @DisplayName("should not set action if null")
        void shouldNotSetActionIfNull() {
            try (var ctx = BillingMDC.context(1L, "sub_123", null)) {
                assertThat(MDC.get(BillingMDC.ACTION)).isNull();
            }
        }

        @Test
        @DisplayName("should not set action if blank")
        void shouldNotSetActionIfBlank() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "  ")) {
                assertThat(MDC.get(BillingMDC.ACTION)).isNull();
            }
        }

        @Test
        @DisplayName("should clear MDC when context is closed")
        void shouldClearMdcWhenClosed() {
            try (var ctx = BillingMDC.context(42L, "sub_123", "upgrade")) {
                assertThat(MDC.get(BillingMDC.USER_ID)).isNotNull();
            }

            assertThat(MDC.get(BillingMDC.USER_ID)).isNull();
            assertThat(MDC.get(BillingMDC.SUBSCRIPTION_ID)).isNull();
            assertThat(MDC.get(BillingMDC.ACTION)).isNull();
        }
    }

    @Nested
    @DisplayName("webhookContext()")
    class WebhookContextTests {

        @Test
        @DisplayName("should set stripe event ID in MDC")
        void shouldSetStripeEventIdInMdc() {
            try (var ctx = BillingMDC.webhookContext("evt_123", "customer.subscription.updated")) {
                assertThat(MDC.get(BillingMDC.STRIPE_EVENT_ID)).isEqualTo("evt_123");
            }
        }

        @Test
        @DisplayName("should set action with webhook prefix in MDC")
        void shouldSetActionWithWebhookPrefix() {
            try (var ctx = BillingMDC.webhookContext("evt_123", "customer.subscription.updated")) {
                assertThat(MDC.get(BillingMDC.ACTION)).isEqualTo("webhook:customer.subscription.updated");
            }
        }

        @Test
        @DisplayName("should not set event ID if null")
        void shouldNotSetEventIdIfNull() {
            try (var ctx = BillingMDC.webhookContext(null, "event.type")) {
                assertThat(MDC.get(BillingMDC.STRIPE_EVENT_ID)).isNull();
            }
        }

        @Test
        @DisplayName("should not set action if event type is null")
        void shouldNotSetActionIfEventTypeIsNull() {
            try (var ctx = BillingMDC.webhookContext("evt_123", null)) {
                assertThat(MDC.get(BillingMDC.ACTION)).isNull();
            }
        }

        @Test
        @DisplayName("should clear MDC when context is closed")
        void shouldClearMdcWhenClosed() {
            try (var ctx = BillingMDC.webhookContext("evt_123", "type")) {
                assertThat(MDC.get(BillingMDC.STRIPE_EVENT_ID)).isNotNull();
            }

            assertThat(MDC.get(BillingMDC.STRIPE_EVENT_ID)).isNull();
            assertThat(MDC.get(BillingMDC.ACTION)).isNull();
        }
    }

    @Nested
    @DisplayName("withPlanCode()")
    class WithPlanCodeTests {

        @Test
        @DisplayName("should set plan code in MDC")
        void shouldSetPlanCodeInMdc() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "upgrade").withPlanCode("PRO")) {
                assertThat(MDC.get(BillingMDC.PLAN_CODE)).isEqualTo("PRO");
            }
        }

        @Test
        @DisplayName("should not set plan code if null")
        void shouldNotSetPlanCodeIfNull() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "upgrade").withPlanCode(null)) {
                assertThat(MDC.get(BillingMDC.PLAN_CODE)).isNull();
            }
        }

        @Test
        @DisplayName("should return self for chaining")
        void shouldReturnSelfForChaining() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "upgrade")
                    .withPlanCode("PRO")
                    .withCustomerId("cus_123")) {
                assertThat(MDC.get(BillingMDC.PLAN_CODE)).isEqualTo("PRO");
                assertThat(MDC.get(BillingMDC.STRIPE_CUSTOMER_ID)).isEqualTo("cus_123");
            }
        }
    }

    @Nested
    @DisplayName("withCustomerId()")
    class WithCustomerIdTests {

        @Test
        @DisplayName("should set customer ID in MDC")
        void shouldSetCustomerIdInMdc() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "upgrade").withCustomerId("cus_abc")) {
                assertThat(MDC.get(BillingMDC.STRIPE_CUSTOMER_ID)).isEqualTo("cus_abc");
            }
        }

        @Test
        @DisplayName("should not set customer ID if null")
        void shouldNotSetCustomerIdIfNull() {
            try (var ctx = BillingMDC.context(1L, "sub_123", "upgrade").withCustomerId(null)) {
                assertThat(MDC.get(BillingMDC.STRIPE_CUSTOMER_ID)).isNull();
            }
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("should remove all billing MDC entries")
        void shouldRemoveAllBillingMdcEntries() {
            MDC.put(BillingMDC.USER_ID, "1");
            MDC.put(BillingMDC.SUBSCRIPTION_ID, "sub_123");
            MDC.put(BillingMDC.ACTION, "upgrade");
            MDC.put(BillingMDC.PLAN_CODE, "PRO");
            MDC.put(BillingMDC.STRIPE_EVENT_ID, "evt_123");
            MDC.put(BillingMDC.STRIPE_CUSTOMER_ID, "cus_123");

            BillingMDC.clear();

            assertThat(MDC.get(BillingMDC.USER_ID)).isNull();
            assertThat(MDC.get(BillingMDC.SUBSCRIPTION_ID)).isNull();
            assertThat(MDC.get(BillingMDC.ACTION)).isNull();
            assertThat(MDC.get(BillingMDC.PLAN_CODE)).isNull();
            assertThat(MDC.get(BillingMDC.STRIPE_EVENT_ID)).isNull();
            assertThat(MDC.get(BillingMDC.STRIPE_CUSTOMER_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("should have correct constant values")
        void shouldHaveCorrectConstantValues() {
            assertThat(BillingMDC.USER_ID).isEqualTo("billing.userId");
            assertThat(BillingMDC.SUBSCRIPTION_ID).isEqualTo("billing.subscriptionId");
            assertThat(BillingMDC.ACTION).isEqualTo("billing.action");
            assertThat(BillingMDC.PLAN_CODE).isEqualTo("billing.planCode");
            assertThat(BillingMDC.STRIPE_EVENT_ID).isEqualTo("billing.stripeEventId");
            assertThat(BillingMDC.STRIPE_CUSTOMER_ID).isEqualTo("billing.stripeCustomerId");
        }
    }
}
