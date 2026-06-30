package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Subscription Domain Model Tests")
class SubscriptionTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should set default values on creation")
        void shouldSetDefaultValuesOnCreation() {
            Subscription subscription = new Subscription();

            assertThat(subscription.getCreatedAt()).isNotNull();
            assertThat(subscription.getUpdatedAt()).isNotNull();
            assertThat(subscription.getQuantity()).isEqualTo(1);
            assertThat(subscription.getCancelAtPeriodEnd()).isFalse();
            assertThat(subscription.getProvider()).isEqualTo("stripe");
        }
    }

    @Nested
    @DisplayName("isActive()")
    class IsActiveTests {

        @Test
        @DisplayName("should return true for 'active' status")
        void shouldReturnTrueForActiveStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("active");

            assertThat(subscription.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return true for 'trialing' status")
        void shouldReturnTrueForTrialingStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("trialing");

            assertThat(subscription.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false for 'canceled' status")
        void shouldReturnFalseForCanceledStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("canceled");

            assertThat(subscription.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false for 'past_due' status")
        void shouldReturnFalseForPastDueStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("past_due");

            assertThat(subscription.isActive()).isFalse();
        }

        @Test
        @DisplayName("should return false for 'incomplete' status")
        void shouldReturnFalseForIncompleteStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("incomplete");

            assertThat(subscription.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("isCanceled()")
    class IsCanceledTests {

        @Test
        @DisplayName("should return true for 'canceled' status")
        void shouldReturnTrueForCanceledStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("canceled");

            assertThat(subscription.isCanceled()).isTrue();
        }

        @Test
        @DisplayName("should return false for 'active' status")
        void shouldReturnFalseForActiveStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("active");

            assertThat(subscription.isCanceled()).isFalse();
        }
    }

    @Nested
    @DisplayName("isPastDue()")
    class IsPastDueTests {

        @Test
        @DisplayName("should return true for 'past_due' status")
        void shouldReturnTrueForPastDueStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("past_due");

            assertThat(subscription.isPastDue()).isTrue();
        }

        @Test
        @DisplayName("should return false for 'active' status")
        void shouldReturnFalseForActiveStatus() {
            Subscription subscription = new Subscription();
            subscription.setStatus("active");

            assertThat(subscription.isPastDue()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            Subscription subscription = new Subscription();
            Plan plan = new Plan("PRO", "Pro", "Pro plan");
            BillingCustomer customer = new BillingCustomer();
            Price price = new Price();
            LocalDateTime now = LocalDateTime.now();

            subscription.setId(1L);
            subscription.setBillingCustomer(customer);
            subscription.setPlan(plan);
            subscription.setPrice(price);
            subscription.setCadence("monthly");
            subscription.setProvider("stripe");
            subscription.setProviderSubscriptionId("sub_123");
            subscription.setStatus("active");
            subscription.setQuantity(5);
            subscription.setCurrentPeriodStart(now);
            subscription.setCurrentPeriodEnd(now.plusMonths(1));
            subscription.setCancelAtPeriodEnd(true);

            assertThat(subscription.getId()).isEqualTo(1L);
            assertThat(subscription.getBillingCustomer()).isEqualTo(customer);
            assertThat(subscription.getPlan()).isEqualTo(plan);
            assertThat(subscription.getPrice()).isEqualTo(price);
            assertThat(subscription.getCadence()).isEqualTo("monthly");
            assertThat(subscription.getProvider()).isEqualTo("stripe");
            assertThat(subscription.getProviderSubscriptionId()).isEqualTo("sub_123");
            assertThat(subscription.getStatus()).isEqualTo("active");
            assertThat(subscription.getQuantity()).isEqualTo(5);
            assertThat(subscription.getCurrentPeriodStart()).isEqualTo(now);
            assertThat(subscription.getCurrentPeriodEnd()).isEqualTo(now.plusMonths(1));
            assertThat(subscription.getCancelAtPeriodEnd()).isTrue();
        }
    }
}
