package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plan Domain Model Tests")
class PlanTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create plan with default constructor")
        void shouldCreatePlanWithDefaultConstructor() {
            Plan plan = new Plan();

            assertThat(plan.getId()).isNull();
            assertThat(plan.getCode()).isNull();
            assertThat(plan.getName()).isNull();
        }

        @Test
        @DisplayName("should create plan with parameterized constructor")
        void shouldCreatePlanWithParameterizedConstructor() {
            Plan plan = new Plan("PRO", "Pro Plan", "Professional plan");

            assertThat(plan.getCode()).isEqualTo("PRO");
            assertThat(plan.getName()).isEqualTo("Pro Plan");
            assertThat(plan.getDescription()).isEqualTo("Professional plan");
        }
    }

    @Nested
    @DisplayName("isUnlimited()")
    class IsUnlimitedTests {

        @Test
        @DisplayName("should return true for ENTERPRISE plan")
        void shouldReturnTrueForEnterprise() {
            Plan plan = new Plan("ENTERPRISE", "Enterprise", "Enterprise plan");

            assertThat(plan.isUnlimited()).isTrue();
        }

        @Test
        @DisplayName("should return false for FREE plan")
        void shouldReturnFalseForFree() {
            Plan plan = new Plan("FREE", "Free", "Free plan");

            assertThat(plan.isUnlimited()).isFalse();
        }

        @Test
        @DisplayName("should return false for PRO plan")
        void shouldReturnFalseForPro() {
            Plan plan = new Plan("PRO", "Pro", "Pro plan");

            assertThat(plan.isUnlimited()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set PAYG prices")
        void shouldGetAndSetPaygPrices() {
            Plan plan = new Plan();
            plan.setPaygPricePerToolCredit(50L);
            plan.setPaygPricePerLlmToken(10L);
            plan.setPaygPricePerGbMonth(100L);

            assertThat(plan.getPaygPricePerToolCredit()).isEqualTo(50L);
            assertThat(plan.getPaygPricePerLlmToken()).isEqualTo(10L);
            assertThat(plan.getPaygPricePerGbMonth()).isEqualTo(100L);
        }

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            Plan plan = new Plan();
            plan.setId(1L);

            assertThat(plan.getId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("getResourceLimit()")
    class GetResourceLimitTests {

        @Test
        @DisplayName("resolves PUBLICATION to max_publications (the publish cap)")
        void resolvesPublicationLimit() {
            Plan plan = new Plan("PRO", "Pro", "Pro plan");
            plan.setMaxPublications(300);
            plan.setMaxApplications(50);

            assertThat(plan.getResourceLimit("PUBLICATION")).isEqualTo(300);
            // PUBLICATION (published) is distinct from APPLICATION (acquired).
            assertThat(plan.getResourceLimit("APPLICATION")).isEqualTo(50);
        }

        @Test
        @DisplayName("null max_publications = unlimited; unknown type = null")
        void nullAndUnknown() {
            Plan plan = new Plan("ENTERPRISE_GOLD", "Ent", "Enterprise");
            assertThat(plan.getResourceLimit("PUBLICATION")).isNull();   // unlimited
            assertThat(plan.getResourceLimit("BOGUS")).isNull();
            assertThat(plan.getResourceLimit(null)).isNull();
        }
    }
}
