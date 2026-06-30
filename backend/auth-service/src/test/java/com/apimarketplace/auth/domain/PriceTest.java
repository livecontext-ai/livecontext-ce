package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Price Domain Model Tests")
class PriceTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create price with default constructor")
        void shouldCreatePriceWithDefaultConstructor() {
            Price price = new Price();

            assertThat(price.getId()).isNull();
            assertThat(price.getPlan()).isNull();
            assertThat(price.getCurrency()).isEqualTo("usd");
            assertThat(price.getAmountCents()).isEqualTo(0);
            assertThat(price.getProvider()).isEqualTo("stripe");
        }

        @Test
        @DisplayName("should create price with parameterized constructor")
        void shouldCreatePriceWithParameterizedConstructor() {
            Plan plan = new Plan("PRO", "Pro", "Pro plan");
            Price price = new Price(plan, "monthly", 2900);

            assertThat(price.getPlan()).isEqualTo(plan);
            assertThat(price.getCadence()).isEqualTo("monthly");
            assertThat(price.getAmountCents()).isEqualTo(2900);
        }
    }

    @Nested
    @DisplayName("isFree()")
    class IsFreeTests {

        @Test
        @DisplayName("should return true when amountCents is 0")
        void shouldReturnTrueWhenAmountIsZero() {
            Price price = new Price();
            price.setAmountCents(0);

            assertThat(price.isFree()).isTrue();
        }

        @Test
        @DisplayName("should return false when amountCents is positive")
        void shouldReturnFalseWhenAmountIsPositive() {
            Price price = new Price();
            price.setAmountCents(2900);

            assertThat(price.isFree()).isFalse();
        }
    }

    @Nested
    @DisplayName("isPayg()")
    class IsPaygTests {

        @Test
        @DisplayName("should return true for payg cadence")
        void shouldReturnTrueForPaygCadence() {
            Price price = new Price();
            price.setCadence("payg");

            assertThat(price.isPayg()).isTrue();
        }

        @Test
        @DisplayName("should return false for monthly cadence")
        void shouldReturnFalseForMonthlyCadence() {
            Price price = new Price();
            price.setCadence("monthly");

            assertThat(price.isPayg()).isFalse();
        }

        @Test
        @DisplayName("should return false for yearly cadence")
        void shouldReturnFalseForYearlyCadence() {
            Price price = new Price();
            price.setCadence("yearly");

            assertThat(price.isPayg()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            Plan plan = new Plan("STARTER", "Starter", "Starter plan");
            Price price = new Price();

            price.setId(1L);
            price.setPlan(plan);
            price.setCadence("yearly");
            price.setCurrency("usd");
            price.setAmountCents(19900);
            price.setProvider("stripe");
            price.setProviderPriceId("price_abc123");

            assertThat(price.getId()).isEqualTo(1L);
            assertThat(price.getPlan()).isEqualTo(plan);
            assertThat(price.getCadence()).isEqualTo("yearly");
            assertThat(price.getCurrency()).isEqualTo("usd");
            assertThat(price.getAmountCents()).isEqualTo(19900);
            assertThat(price.getProvider()).isEqualTo("stripe");
            assertThat(price.getProviderPriceId()).isEqualTo("price_abc123");
        }
    }
}
