package com.apimarketplace.auth.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StripeProperties Tests")
class StripePropertiesTest {

    private StripeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StripeProperties();
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValuesTests {

        @Test
        @DisplayName("should have 'usd' as default currency")
        void shouldHaveUsdAsDefaultCurrency() {
            assertThat(properties.getCurrency()).isEqualTo("usd");
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set secretKey")
        void shouldGetAndSetSecretKey() {
            properties.setSecretKey("sk_test_123");

            assertThat(properties.getSecretKey()).isEqualTo("sk_test_123");
        }

        @Test
        @DisplayName("should get and set webhookSecret")
        void shouldGetAndSetWebhookSecret() {
            properties.setWebhookSecret("whsec_test_123");

            assertThat(properties.getWebhookSecret()).isEqualTo("whsec_test_123");
        }

        @Test
        @DisplayName("should get and set successUrl")
        void shouldGetAndSetSuccessUrl() {
            properties.setSuccessUrl("https://example.com/success");

            assertThat(properties.getSuccessUrl()).isEqualTo("https://example.com/success");
        }

        @Test
        @DisplayName("should get and set cancelUrl")
        void shouldGetAndSetCancelUrl() {
            properties.setCancelUrl("https://example.com/cancel");

            assertThat(properties.getCancelUrl()).isEqualTo("https://example.com/cancel");
        }

        @Test
        @DisplayName("should get and set currency")
        void shouldGetAndSetCurrency() {
            properties.setCurrency("eur");

            assertThat(properties.getCurrency()).isEqualTo("eur");
        }

        @Test
        @DisplayName("should get and set prices map")
        void shouldGetAndSetPricesMap() {
            Map<String, String> prices = Map.of("STARTER", "price_starter_monthly", "PRO", "price_pro_monthly");
            properties.setPrices(prices);

            assertThat(properties.getPrices()).hasSize(2);
            assertThat(properties.getPrices().get("STARTER")).isEqualTo("price_starter_monthly");
        }
    }

    @Nested
    @DisplayName("getPriceId()")
    class GetPriceIdTests {

        @Test
        @DisplayName("should return price ID for known plan")
        void shouldReturnPriceIdForKnownPlan() {
            properties.setPrices(Map.of("PRO", "price_pro_monthly"));

            String priceId = properties.getPriceId("PRO");

            assertThat(priceId).isEqualTo("price_pro_monthly");
        }

        @Test
        @DisplayName("should return null for unknown plan")
        void shouldReturnNullForUnknownPlan() {
            properties.setPrices(Map.of("PRO", "price_pro_monthly"));

            String priceId = properties.getPriceId("UNKNOWN");

            assertThat(priceId).isNull();
        }

        @Test
        @DisplayName("should return null when prices map is null")
        void shouldReturnNullWhenPricesMapIsNull() {
            properties.setPrices(null);

            String priceId = properties.getPriceId("PRO");

            assertThat(priceId).isNull();
        }
    }
}
