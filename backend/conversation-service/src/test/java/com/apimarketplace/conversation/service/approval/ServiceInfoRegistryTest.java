package com.apimarketplace.conversation.service.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServiceInfoRegistry")
class ServiceInfoRegistryTest {

    @Nested
    @DisplayName("getServiceInfo")
    class GetServiceInfo {

        @ParameterizedTest
        @ValueSource(strings = {"gmail", "slack", "github", "stripe", "notion", "discord"})
        @DisplayName("should return known service info for registered services")
        void shouldReturnKnownServiceInfo(String serviceType) {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo(serviceType);
            assertThat(info).isNotNull();
            assertThat(info.serviceType()).isEqualTo(serviceType);
            assertThat(info.serviceName()).isNotBlank();
            assertThat(info.iconSlug()).isNotBlank();
        }

        @Test
        @DisplayName("should return Gmail info correctly")
        void shouldReturnGmailInfo() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo("gmail");
            assertThat(info.serviceName()).isEqualTo("Gmail");
            assertThat(info.iconSlug()).isEqualTo("gmail");
        }

        @Test
        @DisplayName("should return Slack info correctly")
        void shouldReturnSlackInfo() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo("slack");
            assertThat(info.serviceName()).isEqualTo("Slack");
        }

        @Test
        @DisplayName("should handle case-insensitive lookup")
        void shouldHandleCaseInsensitive() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo("GMAIL");
            assertThat(info.serviceType()).isEqualTo("gmail");
            assertThat(info.serviceName()).isEqualTo("Gmail");
        }

        @Test
        @DisplayName("should create fallback info for unknown services")
        void shouldCreateFallbackForUnknown() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo("my_custom_service");
            assertThat(info).isNotNull();
            assertThat(info.serviceType()).isEqualTo("my_custom_service");
            assertThat(info.serviceName()).isEqualTo("My Custom Service");
        }

        @Test
        @DisplayName("should return 'Unknown Service' for null/blank input")
        void shouldReturnUnknownForNull() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo(null);
            assertThat(info.serviceName()).isEqualTo("Unknown Service");

            ServiceInfo blank = ServiceInfoRegistry.getServiceInfo("  ");
            assertThat(blank.serviceName()).isEqualTo("Unknown Service");
        }

        @Test
        @DisplayName("should trim whitespace in service type")
        void shouldTrimWhitespace() {
            ServiceInfo info = ServiceInfoRegistry.getServiceInfo("  gmail  ");
            assertThat(info.serviceName()).isEqualTo("Gmail");
        }
    }

    @Nested
    @DisplayName("isKnownService")
    class IsKnownService {

        @Test
        @DisplayName("should return true for known services")
        void shouldReturnTrueForKnown() {
            assertThat(ServiceInfoRegistry.isKnownService("gmail")).isTrue();
            assertThat(ServiceInfoRegistry.isKnownService("slack")).isTrue();
            assertThat(ServiceInfoRegistry.isKnownService("github")).isTrue();
        }

        @Test
        @DisplayName("should return false for unknown services")
        void shouldReturnFalseForUnknown() {
            assertThat(ServiceInfoRegistry.isKnownService("unknown_service")).isFalse();
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertThat(ServiceInfoRegistry.isKnownService(null)).isFalse();
        }

        @Test
        @DisplayName("should handle case-insensitive checks")
        void shouldHandleCaseInsensitive() {
            assertThat(ServiceInfoRegistry.isKnownService("GMAIL")).isTrue();
            assertThat(ServiceInfoRegistry.isKnownService("GitHub")).isTrue();
        }
    }
}
