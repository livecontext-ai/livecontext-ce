package com.apimarketplace.conversation.service.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServiceInfo")
class ServiceInfoTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            ServiceInfo info = new ServiceInfo("gmail", "Gmail", "Access to email", "gmail-icon");
            assertThat(info.serviceType()).isEqualTo("gmail");
            assertThat(info.serviceName()).isEqualTo("Gmail");
            assertThat(info.description()).isEqualTo("Access to email");
            assertThat(info.iconSlug()).isEqualTo("gmail-icon");
        }

        @Test
        @DisplayName("should create with minimal fields")
        void shouldCreateWithMinimalFields() {
            ServiceInfo info = new ServiceInfo("slack", "Slack");
            assertThat(info.serviceType()).isEqualTo("slack");
            assertThat(info.serviceName()).isEqualTo("Slack");
            assertThat(info.description()).isNull();
            assertThat(info.iconSlug()).isNull();
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("fromType should capitalize first letter")
        void fromTypeShouldCapitalize() {
            ServiceInfo info = ServiceInfo.fromType("gmail");
            assertThat(info.serviceType()).isEqualTo("gmail");
            assertThat(info.serviceName()).isEqualTo("Gmail");
        }

        @Test
        @DisplayName("of should create with name and description")
        void ofShouldCreateWithNameAndDesc() {
            ServiceInfo info = ServiceInfo.of("stripe", "Stripe", "Payment processing");
            assertThat(info.serviceType()).isEqualTo("stripe");
            assertThat(info.serviceName()).isEqualTo("Stripe");
            assertThat(info.description()).isEqualTo("Payment processing");
            assertThat(info.iconSlug()).isNull();
        }
    }
}
