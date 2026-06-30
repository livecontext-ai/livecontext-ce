package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BillingCustomer Domain Model Tests")
class BillingCustomerTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            BillingCustomer customer = new BillingCustomer();

            assertThat(customer.getId()).isNull();
            assertThat(customer.getUser()).isNull();
            assertThat(customer.getProvider()).isNull();
        }

        @Test
        @DisplayName("should create with user and provider")
        void shouldCreateWithUserAndProvider() {
            User user = new User();
            user.setId(1L);

            BillingCustomer customer = new BillingCustomer(user, "stripe");

            assertThat(customer.getUser()).isEqualTo(user);
            assertThat(customer.getProvider()).isEqualTo("stripe");
        }
    }

    @Nested
    @DisplayName("equals()")
    class EqualsTests {

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToItself() {
            BillingCustomer customer = new BillingCustomer();
            customer.setId(1L);

            assertThat(customer).isEqualTo(customer);
        }

        @Test
        @DisplayName("should be equal when same id and user")
        void shouldBeEqualWhenSameIdAndUser() {
            User user = new User();
            user.setId(1L);

            BillingCustomer customer1 = new BillingCustomer();
            customer1.setId(1L);
            customer1.setUser(user);

            BillingCustomer customer2 = new BillingCustomer();
            customer2.setId(1L);
            customer2.setUser(user);

            assertThat(customer1).isEqualTo(customer2);
        }

        @Test
        @DisplayName("should not be equal when different id")
        void shouldNotBeEqualWhenDifferentId() {
            BillingCustomer customer1 = new BillingCustomer();
            customer1.setId(1L);

            BillingCustomer customer2 = new BillingCustomer();
            customer2.setId(2L);

            assertThat(customer1).isNotEqualTo(customer2);
        }

        @Test
        @DisplayName("should not be equal to null")
        void shouldNotBeEqualToNull() {
            BillingCustomer customer = new BillingCustomer();
            customer.setId(1L);

            assertThat(customer).isNotEqualTo(null);
        }

        @Test
        @DisplayName("should not be equal to different type")
        void shouldNotBeEqualToDifferentType() {
            BillingCustomer customer = new BillingCustomer();
            customer.setId(1L);

            assertThat(customer).isNotEqualTo("not a customer");
        }
    }

    @Nested
    @DisplayName("hashCode()")
    class HashCodeTests {

        @Test
        @DisplayName("should have same hashcode for equal objects")
        void shouldHaveSameHashcodeForEqualObjects() {
            BillingCustomer customer1 = new BillingCustomer();
            customer1.setId(1L);

            BillingCustomer customer2 = new BillingCustomer();
            customer2.setId(1L);

            assertThat(customer1.hashCode()).isEqualTo(customer2.hashCode());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            User user = new User();
            user.setId(42L);

            BillingCustomer customer = new BillingCustomer(user, "stripe");
            customer.setId(1L);

            String result = customer.toString();

            assertThat(result).contains("id=1");
            assertThat(result).contains("userId=42");
            assertThat(result).contains("provider='stripe'");
        }

        @Test
        @DisplayName("should handle null user")
        void shouldHandleNullUser() {
            BillingCustomer customer = new BillingCustomer();
            customer.setId(1L);

            String result = customer.toString();

            assertThat(result).contains("userId=null");
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set providerCustomerId")
        void shouldGetAndSetProviderCustomerId() {
            BillingCustomer customer = new BillingCustomer();
            customer.setProviderCustomerId("cus_123");

            assertThat(customer.getProviderCustomerId()).isEqualTo("cus_123");
        }
    }
}
